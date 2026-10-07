# On-device face matching

The app runs YuNet detection and SFace recognition using the pinned OpenCV Android 4.13.0 AAR. Detection locates faces and five landmarks. SFace generates 128-value face representations for optional, enrolled participants. Recognition never verifies a connection or grants access.

## Weights and licenses

Both unmodified weights are from [OpenCV Zoo revision 47534e27](https://github.com/opencv/opencv_zoo/tree/47534e27c9851bb1128ccc0102f1145e27f23f98). Weight licenses were checked in each model directory, independently of the runtime license:

| Asset | Weight license | SHA-256 |
| --- | --- | --- |
| `face_detection_yunet_2023mar.onnx`, 232,589 bytes | [MIT, Shiqi Yu](https://github.com/opencv/opencv_zoo/blob/47534e27c9851bb1128ccc0102f1145e27f23f98/models/face_detection_yunet/LICENSE) | `8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4` |
| `face_recognition_sface_2021dec.onnx`, 38,696,353 bytes | [Apache 2.0](https://github.com/opencv/opencv_zoo/blob/47534e27c9851bb1128ccc0102f1145e27f23f98/models/face_recognition_sface/LICENSE) | `0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79` |

The [SFace model README](https://github.com/opencv/opencv_zoo/blob/47534e27c9851bb1128ccc0102f1145e27f23f98/models/face_recognition_sface/README.md) explicitly applies Apache 2.0 to every file in that directory, including weights. It credits Yaoyao Zhong and Chengrui Wang and identifies MobileFaceNet trained with SFace loss. This is the distributor's weight license statement, not an independent audit of training-data consent or a guarantee of performance. OpenCV runtime has its own [Apache 2.0 license](https://github.com/opencv/opencv/blob/4.13.0/LICENSE). Copies and notices ship in `app/src/main/assets/licenses/`.

## Preprocessing and decisions

1. Android `ImageDecoder` reads only the supplied URI, applies EXIF rotation and reflection, converts to software sRGB pixels, and limits the longest dimension to 1,280 pixels. [AOSP orientation tests](https://android.googlesource.com/platform/cts/+/1134144cdb49ef7350e5ffb2a24f930f188cf6b3/tests/tests/graphics/src/android/graphics/cts/ImageDecoderTest.java) cover its orientation contract. The app also tests all eight orientations.
2. RGBA becomes BGR for YuNet, with score threshold 0.9 and nonmaximum suppression 0.3. Up to 64 faces per photo are processed. Landmarks feed OpenCV `FaceRecognizerSF.alignCrop`, which performs a similarity transform to the model's canonical five landmarks and 112 by 112 crop.
3. OpenCV `FaceRecognizerSF.feature` converts BGR to RGB float32, with scale 1 and zero mean. Pixel normalization is inside the supplied model. Do not add a second normalization. The [OpenCV implementation](https://github.com/opencv/opencv/blob/4.13.0/modules/objdetect/src/face_recognize.cpp) defines this contract. Output vectors are L2 normalized before storage or comparison.
4. A source phone compares only opted-in event participants. Best cosine similarity at least 0.55, a margin of at least 0.08 over the next participant, and acceptable image quality produce a suggestion. Similarity from 0.35 upward produces an uncertain result when these conditions fail. Below 0.35 or no enrolled references means no match. Scores are not calibrated confidence percentages.
5. Small faces, low light, overexposure, blur, weak detections, and compressed eye landmarks flag poor quality. These are simple conservative filters, not reliable occlusion or pose classifiers. Poor-quality faces cannot produce a normal suggestion and are unsuitable for enrolment.

Thresholds are deliberately cautious product defaults. They have not been calibrated for the friend group or low-light pandal photos. Group photos, masks, similar-looking people, demographic variation, and small or turned faces can still fail. Users must confirm or reject suggestions and can correct them manually. Never use this pipeline for authentication.

## Offline setup and verification

Weights and native runtime are bundled in the APK. Recognition itself needs no download or hosted API. The first use copies checksum-verified weights to the app's backup-excluded directory. No images or face vectors are sent by this recognition component. This does not describe the network behavior of Nearby or Google Play services.

The repository contains the weights, so ordinary builds need no model download. To restore or verify them, run `python scripts/fetch_models.py`. Fetching requires internet; using installed bundled weights does not. The script downloads only immutable URLs and refuses unexpected SHA-256 values.

Run `./gradlew testDebugUnitTest connectedDebugAndroidTest` for Kotlin matching rules, Android preprocessing, and actual Android inference. An emulator or device is required for connected tests. A separate host smoke check is available:

```text
python -m pip install -r scripts/requirements-recognition.txt
python scripts/verify_recognition.py
```

The host check executes the actual models but does not establish Android behavior. The [public-domain NASA astronaut fixture](https://scikit-image.org/docs/stable/api/skimage.data.html#skimage.data.astronaut) and its attribution are under `app/src/androidTest/assets/`. Tests exercise JPEG recompression using a synthetic participant key, multiple detections, empty images, and invalid input; no app user's photos or stored face data are included. These checks establish model execution and preprocessing, not real-world identification accuracy.
