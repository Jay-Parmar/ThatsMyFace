"""Restore the pinned, redistributable model assets and verify their hashes."""

import hashlib
from pathlib import Path
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
REVISION = "47534e27c9851bb1128ccc0102f1145e27f23f98"
MODELS = {
    "face_detection_yunet_2023mar.onnx": (
        "face_detection_yunet",
        "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4",
    ),
    "face_recognition_sface_2021dec.onnx": (
        "face_recognition_sface",
        "0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79",
    ),
}


def checksum(path):
    with path.open("rb") as source:
        digest = hashlib.sha256()
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
        return digest.hexdigest()


def verify_models():
    for name, (_, expected) in MODELS.items():
        path = ROOT / "app/src/main/assets/models" / name
        if not path.is_file() or checksum(path) != expected:
            raise ValueError(f"Missing or corrupt model: {name}. Run scripts/fetch_models.py.")


def main():
    for name, (directory, expected) in MODELS.items():
        destination = ROOT / "app/src/main/assets/models" / name
        if destination.exists() and checksum(destination) == expected:
            print(f"Verified {name}")
            continue
        destination.parent.mkdir(parents=True, exist_ok=True)
        temporary = destination.with_suffix(".download")
        url = f"https://media.githubusercontent.com/media/opencv/opencv_zoo/{REVISION}/models/{directory}/{name}"
        try:
            urllib.request.urlretrieve(url, temporary)
            if checksum(temporary) != expected:
                raise ValueError(f"Model checksum mismatch: {name}")
            temporary.replace(destination)
            print(f"Restored {name}")
        finally:
            temporary.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
