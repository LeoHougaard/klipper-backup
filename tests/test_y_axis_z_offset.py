import collections
import importlib.util
from pathlib import Path
import sys
import types
import unittest


ROOT = Path(__file__).resolve().parents[1]
MODULE_PATH = ROOT / "klipper_extras" / "y_axis_z_offset.py"


def load_module():
    package = types.ModuleType("extras")
    package.__path__ = []
    manual_probe = types.ModuleType("extras.manual_probe")
    manual_probe.ProbeResult = collections.namedtuple(
        "probe_result", "bed_x bed_y bed_z test_x test_y test_z"
    )
    sys.modules["extras"] = package
    sys.modules["extras.manual_probe"] = manual_probe
    spec = importlib.util.spec_from_file_location(
        "extras.y_axis_z_offset", MODULE_PATH
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module, manual_probe


class YAxisZOffsetTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.module, cls.manual_probe = load_module()

    def setUp(self):
        self.compensation = object.__new__(self.module.YAxisZOffset)
        self.compensation.y_min = 10.0
        self.compensation.y_max = 110.0
        self.compensation.front_offset = 0.2
        self.compensation.rear_offset = -0.03

    def test_interpolation_and_endpoint_clamping(self):
        values = [
            self.compensation._offset_at_y(y_pos)
            for y_pos in (0.0, 10.0, 60.0, 110.0, 120.0)
        ]
        expected = [0.2, 0.2, 0.085, -0.03, -0.03]
        for actual, wanted in zip(values, expected):
            self.assertAlmostEqual(actual, wanted)

    def test_probe_results_are_corrected_before_mesh_consumption(self):
        result_type = self.manual_probe.ProbeResult
        results = [
            result_type(60.0, 10.0, -0.15, 60.0, 10.0, 5.0),
            result_type(60.0, 60.0, 0.0, 60.0, 60.0, 5.0),
            result_type(60.0, 110.0, -0.05, 60.0, 110.0, 5.0),
        ]
        self.compensation._update_probe_results(results)
        corrected = [result.bed_z for result in results]
        expected = [0.05, 0.085, -0.08]
        for actual, wanted in zip(corrected, expected):
            self.assertAlmostEqual(actual, wanted)

    def test_paper_calibration_preserves_average_and_corrects_tilt(self):
        front, rear = self.compensation._calculate_calibrated_offsets(
            0.4, -0.1
        )
        self.assertAlmostEqual(front, 0.45)
        self.assertAlmostEqual(rear, -0.28)
        self.assertAlmostEqual(front + rear, 0.2 - 0.03)


if __name__ == "__main__":
    unittest.main()
