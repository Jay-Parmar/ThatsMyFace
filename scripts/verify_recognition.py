"""Execute the bundled models on public-domain test media without logging face data."""

import cv2
import numpy as np

from fetch_models import ROOT, checksum, verify_models


def main():
    verify_models()
    fixture = ROOT / "app/src/androidTest/assets/astronaut.png"
    assert checksum(fixture) == "88431cd9653ccd539741b555fb0a46b61558b301d4110412b5bc28b5e3ea6cb5"
    models = ROOT / "app/src/main/assets/models"
    detector = cv2.FaceDetectorYN.create(
        str(models / "face_detection_yunet_2023mar.onnx"), "", (320, 320), .9, .3, 5000
    )
    recognizer = cv2.FaceRecognizerSF.create(str(models / "face_recognition_sface_2021dec.onnx"), "")

    def extract(image):
        detector.setInputSize((image.shape[1], image.shape[0]))
        _, faces = detector.detect(image)
        if faces is None:
            return []
        results = []
        for face in faces:
            aligned = recognizer.alignCrop(image, face)
            assert aligned.shape == (112, 112, 3)
            feature = recognizer.feature(aligned)
            assert feature.shape == (1, 128) and np.isfinite(feature).all()
            results.append(feature)
        return results

    portrait = cv2.imread(str(fixture))
    original = extract(portrait)
    assert len(original) == 1
    ok, jpeg = cv2.imencode(".jpg", portrait, [cv2.IMWRITE_JPEG_QUALITY, 92])
    assert ok
    transformed = extract(cv2.imdecode(jpeg, cv2.IMREAD_COLOR))
    assert len(transformed) == 1
    assert recognizer.match(original[0], transformed[0], cv2.FaceRecognizerSF_FR_COSINE) > .9
    assert len(extract(np.concatenate([portrait, portrait], axis=1))) == 2
    assert extract(np.zeros((320, 320, 3), dtype=np.uint8)) == []
    print("PASS: model checksums, detection, five-landmark alignment, 128-value inference, "
          "same-person JPEG match, two faces, and no-face image.")


if __name__ == "__main__":
    main()
