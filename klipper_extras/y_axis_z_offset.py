# Smooth, runtime-adjustable Z compensation between the front and rear of a bed.
#
# This module wraps the existing G-Code move transform (normally bed_mesh). It
# intentionally affects commanded print moves, not probe results, so changes can
# be tuned during a first layer without regenerating the mesh.

import logging
import math


class YAxisZOffset:
    def __init__(self, config):
        self.printer = config.get_printer()
        self.gcode = self.printer.lookup_object("gcode")
        self.y_min = config.getfloat("y_min")
        self.y_max = config.getfloat("y_max", above=self.y_min)
        self.max_abs_offset = config.getfloat(
            "max_abs_offset", 0.20, above=0.0
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

        # This section is intentionally included after [bed_mesh]. Wrapping the
        # existing transform preserves normal mesh compensation underneath this
        # small, user-tunable front/rear correction.
        self.gcode_move = self.printer.load_object(config, "gcode_move")
        self.next_transform = self.gcode_move.set_move_transform(
            self, force=True
        )

        self.gcode.register_command(
            "SET_Y_AXIS_Z_OFFSET",
            self.cmd_SET_Y_AXIS_Z_OFFSET,
            desc=self.cmd_SET_Y_AXIS_Z_OFFSET_help,
        )
        self.printer.register_event_handler(
            "klippy:ready", self._handle_ready
        )

    def _handle_ready(self):
        save_variables = self.printer.lookup_object("save_variables", None)
        if save_variables is not None:
            variables = save_variables.get_status(None).get("variables", {})
            self.front_offset = self._load_saved_value(
                variables, self.front_variable, self.front_offset
            )
            self.rear_offset = self._load_saved_value(
                variables, self.rear_variable, self.rear_offset
            )
        self.gcode_move.reset_last_position()

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

    def get_position(self):
        position = list(self.next_transform.get_position())
        position[2] -= self._offset_at_y(position[1])
        return position

    def move(self, newpos, speed):
        position = list(newpos)
        position[2] += self._offset_at_y(position[1])
        self.next_transform.move(position, speed)

    cmd_SET_Y_AXIS_Z_OFFSET_help = (
        "Set live front/rear Y-dependent Z offsets"
    )

    def cmd_SET_Y_AXIS_Z_OFFSET(self, gcmd):
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
        self.gcode_move.reset_last_position()
        gcmd.respond_info(
            "Y-axis Z offsets updated: front %.4f mm, rear %.4f mm"
            % (self.front_offset, self.rear_offset)
        )

    def get_status(self, eventtime):
        return {
            "front_offset": self.front_offset,
            "rear_offset": self.rear_offset,
            "y_min": self.y_min,
            "y_max": self.y_max,
        }


def load_config(config):
    return YAxisZOffset(config)
