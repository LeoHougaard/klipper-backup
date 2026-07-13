# Smooth probe-result compensation between the front and rear of a bed.
#
# Correcting probe results makes bed meshes and screw-tilt measurements reflect
# the manually calibrated nozzle-to-bed relationship. A fresh mesh is required
# after changing either endpoint.

import logging
import math

from . import manual_probe


class YAxisZOffset:
    def __init__(self, config):
        self.printer = config.get_printer()
        self.gcode = self.printer.lookup_object("gcode")
        self.y_min = config.getfloat("y_min")
        self.y_max = config.getfloat("y_max", above=self.y_min)
        self.max_abs_offset = config.getfloat(
            "max_abs_offset", 1.0, above=0.0
        )
        self.front_offset = config.getfloat(
            "front_offset", 0.0,
            minval=-self.max_abs_offset,
            maxval=self.max_abs_offset,
        )
        self.rear_offset = config.getfloat(
            "rear_offset", 0.0,
            minval=-self.max_abs_offset,
            maxval=self.max_abs_offset,
        )
        self.front_variable = config.get(
            "front_variable", "y_axis_z_offset_front"
        )
        self.rear_variable = config.get(
            "rear_variable", "y_axis_z_offset_rear"
        )
        self.calibrate_x = config.getfloat("calibrate_x", 60.0)
        self.horizontal_move_z = config.getfloat(
            "horizontal_move_z", 5.0, above=0.0
        )
        self.calibrate_speed = config.getfloat(
            "calibrate_speed", 50.0, above=0.0
        )
        self.calibration_results = []
        self.calibration_index = None
        self.calibration_gcmd = None
        self.calibration_probe_count = 5

        self.bed_mesh = self.printer.load_object(config, "bed_mesh")
        self.printer.register_event_handler(
            "probe:update_results", self._update_probe_results
        )

        self.gcode.register_command(
            "SET_Y_AXIS_Z_OFFSET",
            self.cmd_SET_Y_AXIS_Z_OFFSET,
            desc=self.cmd_SET_Y_AXIS_Z_OFFSET_help,
        )
        self.gcode.register_command(
            "Y_AXIS_Z_OFFSET_CALIBRATE",
            self.cmd_Y_AXIS_Z_OFFSET_CALIBRATE,
            desc=self.cmd_Y_AXIS_Z_OFFSET_CALIBRATE_help,
        )
        self.printer.register_event_handler(
            "klippy:ready", self._handle_ready
        )

    def _handle_ready(self):
        self.toolhead = self.printer.lookup_object("toolhead")
        save_variables = self.printer.lookup_object("save_variables", None)
        if save_variables is not None:
            eventtime = self.printer.get_reactor().monotonic()
            variables = save_variables.get_status(eventtime).get(
                "variables", {}
            )
            self.front_offset = self._load_saved_value(
                variables, self.front_variable, self.front_offset
            )
            self.rear_offset = self._load_saved_value(
                variables, self.rear_variable, self.rear_offset
            )

    def _update_probe_results(self, poslist):
        for index, result in enumerate(poslist):
            correction = self._offset_at_y(result.test_y)
            poslist[index] = manual_probe.ProbeResult(
                result.bed_x,
                result.bed_y,
                result.bed_z + correction,
                result.test_x,
                result.test_y,
                result.test_z,
            )

    def _manual_move(self, position):
        self.toolhead.manual_move(position, self.calibrate_speed)

    def _move_to_calibration_point(self, index):
        y_pos = self.y_min if index == 0 else self.y_max
        point_name = "front" if index == 0 else "rear"
        self._manual_move([None, None, self.horizontal_move_z])
        self._manual_move([self.calibrate_x, y_pos, None])
        self.gcode.respond_info(
            "%s paper test at X%.1f Y%.1f. Use Mainsail's Z controls, "
            "then press ACCEPT."
            % (point_name.capitalize(), self.calibrate_x, y_pos)
        )
        manual_probe.ManualProbeHelper(
            self.printer,
            self.calibration_gcmd,
            self._manual_probe_callback,
        )

    def _manual_probe_callback(self, mpresult):
        if mpresult is None:
            self._manual_move([None, None, self.horizontal_move_z])
            self.gcode.respond_info("Front/rear Z calibration aborted.")
            self.calibration_index = None
            self.calibration_results = []
            return

        # Convert the physical paper-contact position back through the active,
        # compensated bed mesh. The resulting logical Z is the remaining
        # front/rear error that the endpoint corrections need to equalize.
        logical_contact_z = self.bed_mesh.get_position()[2]
        self.calibration_results.append(logical_contact_z)
        self._manual_move([None, None, self.horizontal_move_z])

        if self.calibration_index == 0:
            self.calibration_index = 1
            self._move_to_calibration_point(1)
            return

        front, rear = self._calculate_calibrated_offsets(
            self.calibration_results[0], self.calibration_results[1]
        )
        if (abs(front) > self.max_abs_offset
                or abs(rear) > self.max_abs_offset):
            self.gcode.respond_info(
                "Calibration result exceeds the %.3f mm safety limit; "
                "offsets were not changed."
                % (self.max_abs_offset,)
            )
            self.calibration_index = None
            self.calibration_results = []
            return

        self.front_offset = front
        self.rear_offset = rear
        center_y = (self.y_min + self.y_max) / 2.0
        self._manual_move([self.calibrate_x, center_y, None])
        probe_count = self.calibration_probe_count
        self.calibration_index = None
        self.calibration_results = []
        self.gcode.respond_info(
            "Paper calibration complete: front %.4f mm, rear %.4f mm. "
            "Rebuilding a compensated %dx%d heightmap."
            % (front, rear, probe_count, probe_count)
        )
        self.gcode.run_script_from_command(
            "SAVE_VARIABLE VARIABLE=%s VALUE=%.6f\n"
            "SAVE_VARIABLE VARIABLE=%s VALUE=%.6f\n"
            "BED_MESH_CLEAR\n"
            "BED_MESH_CALIBRATE PROBE_COUNT=%d,%d"
            % (
                self.front_variable,
                front,
                self.rear_variable,
                rear,
                probe_count,
                probe_count,
            )
        )
        self.gcode.respond_info(
            "Front/rear Z calibration complete. The displayed heightmap "
            "includes the saved correction."
        )

    def _calculate_calibrated_offsets(self, front_contact, rear_contact):
        contact_average = (front_contact + rear_contact) / 2.0
        return (
            self.front_offset + front_contact - contact_average,
            self.rear_offset + rear_contact - contact_average,
        )

    def _load_saved_value(self, variables, name, fallback):
        if name not in variables:
            return fallback
        try:
            value = float(variables[name])
        except (TypeError, ValueError):
            logging.warning(
                "y_axis_z_offset: ignoring invalid saved %s=%r",
                name, variables[name],
            )
            return fallback
        if not math.isfinite(value) or abs(value) > self.max_abs_offset:
            logging.warning(
                "y_axis_z_offset: ignoring out-of-range saved %s=%.6f",
                name, value,
            )
            return fallback
        return value

    def _offset_at_y(self, y_pos):
        ratio = (y_pos - self.y_min) / (self.y_max - self.y_min)
        ratio = max(0.0, min(1.0, ratio))
        return self.front_offset + ratio * (
            self.rear_offset - self.front_offset
        )

    cmd_SET_Y_AXIS_Z_OFFSET_help = (
        "Set front/rear probe correction used by the next bed mesh"
    )

    cmd_Y_AXIS_Z_OFFSET_CALIBRATE_help = (
        "Calibrate front/rear Z offsets with two manual paper tests"
    )

    def cmd_Y_AXIS_Z_OFFSET_CALIBRATE(self, gcmd):
        if self.calibration_index is not None:
            raise gcmd.error("Front/rear Z calibration is already active")
        eventtime = self.printer.get_reactor().monotonic()
        homed_axes = self.toolhead.get_status(eventtime).get(
            "homed_axes", ""
        )
        if not all(axis in homed_axes for axis in "xyz"):
            raise gcmd.error("Home XYZ before front/rear Z calibration")
        if self.bed_mesh.z_mesh is None:
            raise gcmd.error(
                "Generate or load a bed mesh before front/rear Z calibration"
            )
        print_stats = self.printer.lookup_object("print_stats", None)
        if print_stats is not None:
            state = print_stats.get_status(eventtime).get("state", "")
            if state in ("printing", "paused"):
                raise gcmd.error(
                    "Front/rear Z calibration cannot run during a print"
                )
        manual_probe.verify_no_manual_probe(self.printer)
        probe_count = gcmd.get_int("PROBE_COUNT", 5)
        if probe_count < 3 or probe_count > 15 or probe_count % 2 == 0:
            raise gcmd.error(
                "PROBE_COUNT must be an odd number from 3 through 15"
            )
        self.calibration_results = []
        self.calibration_index = 0
        self.calibration_gcmd = gcmd
        self.calibration_probe_count = probe_count
        self._move_to_calibration_point(0)

    def cmd_SET_Y_AXIS_Z_OFFSET(self, gcmd):
        if self.calibration_index is not None:
            raise gcmd.error(
                "Finish or abort front/rear Z calibration before changing offsets"
            )
        front = gcmd.get_float("FRONT", None)
        rear = gcmd.get_float("REAR", None)
        if front is None and rear is None:
            gcmd.respond_info(
                "Y-axis Z offsets: front %.4f mm, rear %.4f mm"
                % (self.front_offset, self.rear_offset)
            )
            return
        for label, value in (("FRONT", front), ("REAR", rear)):
            if (value is not None
                    and (not math.isfinite(value)
                         or abs(value) > self.max_abs_offset)):
                raise gcmd.error(
                    "%s must be between %.3f and %.3f mm"
                    % (label, -self.max_abs_offset, self.max_abs_offset)
                )
        if front is not None:
            self.front_offset = front
        if rear is not None:
            self.rear_offset = rear
        gcmd.respond_info(
            "Y-axis probe correction updated: front %.4f mm, rear %.4f mm. "
            "Generate a new bed mesh to apply it."
            % (self.front_offset, self.rear_offset)
        )

    def get_status(self, eventtime):
        return {
            "front_offset": self.front_offset,
            "rear_offset": self.rear_offset,
            "y_min": self.y_min,
            "y_max": self.y_max,
            "calibration_point": self.calibration_index,
            "calibration_probe_count": self.calibration_probe_count,
            "mode": "probe_result_compensation",
        }


def load_config(config):
    return YAxisZOffset(config)
