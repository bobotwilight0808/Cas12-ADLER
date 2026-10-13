from strip_reader.config import TEMPLATES
from strip_reader.labels import infer_label, iter_image_paths


def test_dual_target_mapping_t1():
    row = infer_label(".", "dual-target/t1-positive/31.jpg")
    assert row.strip_type == "dual"
    assert row.expected_t1 == "positive"
    assert row.expected_t2 == "negative"
    assert row.include == "yes"


def test_dual_target_mapping_t2():
    row = infer_label(".", "dual-target/t2-positive/32.jpg")
    assert row.expected_t1 == "negative"
    assert row.expected_t2 == "positive"


def test_discarded_single_folder_is_excluded():
    row = infer_label(".", "single-target/single-discarded/double-line-bright/1.jpg")
    assert row.expected_single_result == "positive"
    assert row.is_discarded == "yes"
    assert row.include == "no"


def test_single_first_second_batch_rules():
    assert infer_label(".", "single-target/batch-1/16-control.jpg").expected_single_result == "negative"
    assert infer_label(".", "single-target/batch-1/16-1.jpg").expected_single_result == "positive"
    assert infer_label(".", "single-target/batch-2/18-negative.jpg").expected_single_result == "negative"
    assert infer_label(".", "single-target/batch-2/18-4.jpg").expected_single_result == "positive"


def test_single_third_fourth_batch_rules():
    assert infer_label(".", "single-target/batch-3/26.jpg").expected_single_result == "positive"
    assert infer_label(".", "single-target/batch-3/3x.jpg").expected_single_result == "negative"
    assert infer_label(".", "single-target/batch-4/27.jpg").expected_single_result == "negative"


def test_iter_image_paths_only_scans_raw_source_dirs(tmp_path):
    raw_image = tmp_path / "single-target" / "batch-1" / "1.jpg"
    generated_image = tmp_path / "datasets" / "curated" / "single" / "included" / "positive" / "1.jpg"
    output_image = tmp_path / "outputs" / "debug" / "1.jpg"
    for image_path in (raw_image, generated_image, output_image):
        image_path.parent.mkdir(parents=True, exist_ok=True)
        image_path.write_bytes(b"")

    assert iter_image_paths(tmp_path) == [raw_image]


def test_single_template_places_control_line_near_arrow_end():
    template = TEMPLATES["single"]
    assert template.lines["C"].y > template.lines["T"].y
