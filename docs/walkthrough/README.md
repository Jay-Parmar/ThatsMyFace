# Illustrated walkthrough

The PDF at `docs/ThatsMyFace-Walkthrough.pdf` is generated locally. It contains original vector illustrations, standard PDF Helvetica fonts, and explicitly identified screenshots of the app using test fixtures. It does not contain private phone screenshots, face references, or usable event invitations.

```powershell
python -m venv .tmp/docs-venv
.tmp/docs-venv/Scripts/python.exe -m pip install -r scripts/requirements-docs.txt
.tmp/docs-venv/Scripts/python.exe scripts/build_walkthrough.py --render-dir .tmp/walkthrough-review
```

`scripts/build_walkthrough.py` is the illustration and layout source. Screenshot files and their provenance are listed in `evidence.json`. Update the evidence after checking the final APK. The generator requires all screenshot files unless `--allow-missing` is supplied for an internal draft. Review every rendered page before publishing. Renders and contact sheets belong in the ignored `.tmp` directory.

Each screenshot entry records `file`, `sha256`, and `provenance`. An optional `caption` overrides the page caption. Captures with seeded request, match, or saved-copy records must be identified as UX fixtures. They are not evidence that recognition or a real radio transfer occurred. The generator checks recorded screenshot checksums before embedding them.

The guide's checkboxes and result fields can be completed in a PDF viewer that supports forms. Its contents links and source links are clickable. The guide is intended for screen reading; its dark backgrounds use more ink when printed.

The public-domain NASA portrait of astronaut Eileen Collins is the existing test fixture distributed by scikit-image. Its original source, license statement, and checksum are recorded in `app/src/androidTest/assets/NOTICE.txt`, preserved unchanged here as `NASA-FIXTURE-NOTICE.txt`. The notice describes the original test asset; this guide additionally includes a screenshot of the app executing recognition with that fixture. Its appearance does not imply endorsement. Original vector illustrations are drawn by the generator itself. No stock icons or external fonts are included.

Generation uses [ReportLab](https://docs.reportlab.com/reportlab/userguide/ch2_graphics/). Local render verification uses [pypdfium2](https://pypdfium2.readthedocs.io/en/stable/python_api.html#pypdfium2.PdfPage.render). These are documentation tools and are not app dependencies. No library code or font files are copied into this repository.
