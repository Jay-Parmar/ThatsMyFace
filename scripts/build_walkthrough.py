"""Build the illustrated, locally generated ThatsMyFace field guide."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
from xml.sax.saxutils import escape

from reportlab.lib.colors import HexColor
from reportlab.lib.styles import ParagraphStyle
from reportlab.pdfbase.pdfmetrics import stringWidth
from reportlab.pdfgen import canvas
from reportlab.platypus import Paragraph


ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "docs" / "walkthrough"
WIDTH, HEIGHT = 595.28, 841.89
MARGIN = 38
INK = HexColor("#0D1616")
PANEL = HexColor("#172926")
PANEL_LIGHT = HexColor("#244039")
WHITE = HexColor("#F6F8F4")
MUTED = HexColor("#B5CAC5")
LIME = HexColor("#D7EF8B")
TEAL = HexColor("#96DDD1")
CORAL = HexColor("#FFAD97")
LINE = HexColor("#3D574E")


class Guide:
    def __init__(self, output: Path, evidence: dict, allow_missing: bool):
        self.c = canvas.Canvas(str(output), pagesize=(WIDTH, HEIGHT), pageCompression=1)
        self.c.setTitle("ThatsMyFace | Your night, in focus")
        self.c.setAuthor("ThatsMyFace")
        self.c.setSubject("Illustrated app walkthrough and two-phone test guide")
        self.c.setCreator("ThatsMyFace documentation")
        self.evidence = evidence
        self.allow_missing = allow_missing
        self.page_number = 0
        self.fields = 0

    def box(self, x, y, width, height, color=PANEL, radius=15, stroke=None):
        self.c.setFillColor(color)
        self.c.setStrokeColor(stroke or color)
        self.c.roundRect(x, HEIGHT - y - height, width, height, radius, fill=1, stroke=bool(stroke))

    def line(self, x1, y1, x2, y2, color=LINE, width=1):
        self.c.setStrokeColor(color)
        self.c.setLineWidth(width)
        self.c.line(x1, HEIGHT - y1, x2, HEIGHT - y2)

    def text(self, content, x, y, size=11, color=WHITE, bold=False):
        self.c.setFillColor(color)
        self.c.setFont("Helvetica-Bold" if bold else "Helvetica", size)
        self.c.drawString(x, HEIGHT - y - size, content)

    def para(self, content, x, y, width, size=11, color=MUTED, leading=None, bold=False):
        style = ParagraphStyle("body", fontName="Helvetica-Bold" if bold else "Helvetica",
                               fontSize=size, leading=leading or size * 1.38, textColor=color)
        paragraph = Paragraph(content, style)
        _, height = paragraph.wrap(width, HEIGHT)
        if y + height > HEIGHT - 40:
            raise ValueError(f"Text overflows page {self.page_number}: {content[:70]}")
        paragraph.drawOn(self.c, x, HEIGHT - y - height)
        return height

    def pill(self, label, x, y, color=LIME, dark=True, size=9):
        width = stringWidth(label, "Helvetica-Bold", size) + 20
        self.box(x, y, width, 24, color if dark else PANEL_LIGHT, radius=12)
        self.text(label, x + 10, y + 6, size, INK if dark else color, True)
        return width

    def number(self, label, x, y, color=LIME, size=26):
        self.c.setFillColor(color)
        self.c.circle(x + size / 2, HEIGHT - y - size / 2, size / 2, fill=1, stroke=0)
        self.text(label, x + 7, y + 6, 10, INK, True)

    def header(self, section, title, subtitle, key):
        self.page_number += 1
        self.c.setFillColor(INK)
        self.c.rect(0, 0, WIDTH, HEIGHT, fill=1, stroke=0)
        self.c.bookmarkPage(key)
        self.c.addOutlineEntry(title.replace("<br/>", " "), key, 0, False)
        self.text("THATSMYFACE", MARGIN, 25, 9, WHITE, True)
        self.text(section.upper(), 370, 25, 8, TEAL, True)
        self.line(MARGIN, 49, WIDTH - MARGIN, 49)
        self.para(title, MARGIN, 71, WIDTH - 2 * MARGIN, 31, WHITE, 34, True)
        self.para(subtitle, MARGIN, 153, WIDTH - 2 * MARGIN, 11.5, MUTED, 16)

    def end(self):
        self.line(MARGIN, 798, WIDTH - MARGIN, 798)
        self.text("IF YOU'RE IN IT, FIND IT.", MARGIN, 811, 8, MUTED, True)
        self.text(f"FIELD GUIDE  /  {self.page_number:02d}", WIDTH - 142, 811, 8, MUTED)
        self.c.showPage()

    def photo_art(self, x, y, width, height, color=TEAL, people=2):
        self.box(x, y, width, height, PANEL_LIGHT, radius=9)
        self.c.setFillColor(color)
        self.c.circle(x + width * 0.78, HEIGHT - y - height * 0.24, height * 0.09, fill=1, stroke=0)
        self.line(x + 10, y + height * 0.55, x + width - 10, y + height * 0.55, LINE)
        for i in range(people):
            px = x + width * (i + 1) / (people + 1)
            self.c.setFillColor(color)
            self.c.circle(px, HEIGHT - y - height * 0.48, height * 0.095, fill=1, stroke=0)
            self.c.roundRect(px - height * 0.11, HEIGHT - y - height * 0.84, height * 0.22,
                             height * 0.24, height * 0.08, fill=1, stroke=0)

    def arrow(self, x1, y1, x2, y2, color=TEAL, width=2):
        self.line(x1, y1, x2, y2, color, width)
        angle = math.atan2(y2 - y1, x2 - x1)
        for delta in (-0.48, 0.48):
            self.line(x2, y2, x2 - 8 * math.cos(angle + delta), y2 - 8 * math.sin(angle + delta), color, width)

    def screenshot(self, name, x, y, width, max_height, caption):
        entry = self.evidence.get("screenshots", {}).get(name)
        if entry:
            caption = entry.get("caption", caption)
        path = ASSETS / entry["file"] if entry else None
        if not path or not path.is_file():
            if not self.allow_missing:
                raise FileNotFoundError(f"Required fixture screenshot: {name}")
            self.box(x, y, width, max_height, PANEL_LIGHT, 22, LINE)
            self.para(f"{escape(name.upper())}<br/><br/>Internal draft<br/>Capture pending", x + 20, y + 45,
                      width - 40, 14, MUTED)
            h = max_height
        else:
            from PIL import Image
            if entry.get("sha256") and hashlib.sha256(path.read_bytes()).hexdigest() != entry["sha256"]:
                raise ValueError(f"Screenshot checksum changed: {path}")
            with Image.open(path) as image:
                source_width, source_height = image.size
            factor = min((width - 12) / source_width, (max_height - 12) / source_height)
            w, h = source_width * factor, source_height * factor
            frame_x = x + (width - w - 12) / 2
            self.box(frame_x, y, w + 12, h + 12, PANEL_LIGHT, 18, LINE)
            self.c.drawImage(str(path), frame_x + 6, HEIGHT - y - 6 - h, w, h, mask="auto")
            h += 12
        self.para(caption, x, y + h + 10, width, 8, MUTED, 11)
        return h

    def check(self, name, x, y, tooltip=None):
        self.fields += 1
        self.c.acroForm.checkbox(name=name, tooltip=tooltip or name.replace("_", " "),
                                 x=x, y=HEIGHT - y - 15, size=15, checked=False,
                                 buttonStyle="check", borderWidth=1, borderColor=TEAL,
                                 fillColor=PANEL, textColor=LIME, forceBorder=True)

    def result_field(self, name, label, x, y, width, height=28):
        self.text(label, x, y, 9, MUTED)
        self.c.acroForm.textfield(name=name, tooltip=label, x=x, y=HEIGHT - y - 17 - height,
                                  width=width, height=height, fontName="Helvetica", fontSize=10,
                                  borderWidth=1, borderColor=LINE, fillColor=PANEL,
                                  textColor=WHITE, forceBorder=True,
                                  fieldFlags="multiline" if height > 28 else "")

    def callout(self, label, text, x, y, width, height=79, color=LIME):
        self.box(x, y, width, height)
        self.box(x, y + 13, 3, height - 26, color, 1)
        self.text(label, x + 15, y + 13, 11, color, True)
        self.para(text, x + 15, y + 33, width - 30, 10, WHITE, 14)

    def cover(self):
        self.page_number += 1
        self.c.setFillColor(INK)
        self.c.rect(0, 0, WIDTH, HEIGHT, fill=1, stroke=0)
        self.c.bookmarkPage("cover")
        self.c.addOutlineEntry("Start here", "cover", 0, False)
        self.text("THATSMYFACE", MARGIN, 35, 12, WHITE, True)
        self.pill("THE AFTER-DARK PHOTO FIELD GUIDE", MARGIN, 87, TEAL, False)
        self.text("If you're in it,", MARGIN, 135, 48, WHITE, True)
        self.text("find it.", MARGIN, 191, 64, LIME, True)
        self.para("Your best moments are scattered across your friends' phones.<br/>Bring your own photos home, with their approval.",
                  MARGIN, 281, 455, 15, MUTED, 21)
        for i in range(8):
            x = 20 + i * 84
            self.line(x, 380, x + 42, 346, LINE, 1.2)
            self.line(x + 42, 346, x + 84, 380, LINE, 1.2)
            self.c.setFillColor([TEAL, LIME, CORAL][i % 3])
            self.c.circle(x + 42, HEIGHT - 346, 4, fill=1, stroke=0)
        self.c.saveState()
        self.c.translate(72, HEIGHT - 421)
        self.c.rotate(8)
        self.box(0, HEIGHT, 194, 175, WHITE, 12)
        self.photo_art(9, HEIGHT + 9, 176, 131, TEAL, 3)
        self.text("THE NIGHT CREW", 14, HEIGHT + 149, 10, INK, True)
        self.c.restoreState()
        self.c.saveState()
        self.c.translate(302, HEIGHT - 402)
        self.c.rotate(-7)
        self.box(0, HEIGHT, 203, 195, WHITE, 12)
        self.photo_art(9, HEIGHT + 9, 185, 150, LIME, 2)
        self.text("YES, THAT'S YOU.", 14, HEIGHT + 172, 10, INK, True)
        self.c.restoreState()
        self.arrow(229, 526, 313, 526, LIME, 3)
        self.pill("SELECT", 50, 650, TEAL, False)
        self.pill("VERIFY", 151, 650, TEAL, False)
        self.pill("FIND", 250, 650, TEAL, False)
        self.pill("APPROVE", 333, 650, TEAL, False)
        self.pill("KEEP", 449, 650, TEAL, False)
        self.para("An illustrated walkthrough + a hands-on two-phone test run.<br/>Made for pandal hopping, trips, parties, and the morning after.",
                  MARGIN, 707, 490, 11.5, MUTED, 17)
        self.text(self.evidence["edition"] + "  /  ANDROID " + self.evidence["app_version"].upper(),
                  MARGIN, 768, 8, TEAL, True)
        self.end()

    def map_page(self):
        self.header("01 / Get ready", "One night.<br/>Four places to go.",
                    "Start small: two phones, one event, and six photos you can share.", "map")
        cards = [
            ("01", "Events", "Create a night or scan a friend's invitation. Select the same event on both phones.", "invite", TEAL),
            ("02", "Photos", "Add selected photos, review Of me, and keep track of Requested originals.", "select", LIME),
            ("03", "Friends", "Start nearby sharing. Compare the code before accepting. Manage future access here.", "invite", TEAL),
            ("04", "You", "Choose a nickname, add optional selfies, manage privacy, and leave local feedback.", "privacy", CORAL),
        ]
        for i, (number, title, body, target, accent) in enumerate(cards):
            x, y = MARGIN + (i % 2) * 267, 211 + (i // 2) * 151
            self.box(x, y, 252, 134)
            self.number(number, x + 16, y + 16, accent)
            self.text(title, x + 54, y + 17, 17, WHITE, True)
            self.para(body, x + 16, y + 58, 219, 11, MUTED, 15)
            self.c.linkRect("", target, (x, HEIGHT - y - 134, x + 252, HEIGHT - y), relative=0, thickness=0)
        self.text("BEFORE YOU HEAD OUT", MARGIN, 525, 10, LIME, True)
        items = ["Open and install the supplied APK on both Android 10+ phones.",
                 "Use phones with compatible Google Play services.",
                 "Keep Bluetooth and Wi-Fi on, and both apps open.",
                 "Prepare authorized photos: you, friends, a group, and no face."]
        for i, item in enumerate(items):
            self.check("ready_" + str(i), MARGIN, 553 + i * 31, item)
            self.para(item, MARGIN + 26, 552 + i * 31, 480, 11, WHITE, 15)
        self.callout("Choose first. Connect second.",
                     "Opening Android's photo picker or leaving the app stops sharing. Select your photos and selfies first, then reconnect and verify.",
                     MARGIN, 694, 519, 84, TEAL)
        self.end()

    def invitation_page(self):
        self.header("02 / Meet your friends", "One invitation.<br/>Two verified phones.",
                    "Matching event names are not enough. Join using the same QR invitation.", "invite")
        self.screenshot("friends", 358, 217, 198, 409, "Actual app UI with test identities. Connection results are recorded on page 10.")
        steps = [
            ("1", "PHONE A: CREATE", "In Events, tap Create event and enter a name. Open Show invitation and let your friend scan its QR."),
            ("2", "PHONE B: JOIN", "In Events, open Join event, then Scan invitation QR. Other ways to join offers a saved QR image or pasted invitation."),
            ("3", "BOTH: START", "Open Friends and Start nearby sharing. Allow the nearby permissions when asked. Tap Connect beside your friend."),
            ("4", "COMPARE IN PERSON", "Read the short code on both phones. Tap The codes match on both only when they match. Otherwise reject."),
        ]
        for i, (num, title, body) in enumerate(steps):
            y = 218 + i * 108
            self.number(num, MARGIN, y, LIME if i == 3 else TEAL)
            self.text(title, 76, y + 3, 10, WHITE, True)
            self.para(body, 76, y + 26, 255, 10.5, MUTED, 14)
        self.box(MARGIN, 674, 519, 100)
        self.pill("AFTER PAIRING: VERIFIED AND CONNECTED", 54, 689, LIME)
        self.para("Pair directly with each phone whose photos you want. Friends do not relay another phone's originals. A saved event-access entry is permission, not a live connection.",
                  54, 729, 486, 10.5, WHITE, 14)
        self.end()

    def selection_page(self):
        self.header("03 / Build the album", "A hundred photos.<br/>A little less scrolling.",
                    "Select only the photos you want this event's verified friends to see.", "select")
        self.screenshot("grid", MARGIN, 217, 212, 447, "Actual app UI with synthetic test records. Seeded tags and requests illustrate UX, not recognition or transfer results.")
        x = 273
        self.pill("PHOTOS  >  ON MY PHONE", x, 218, TEAL, False)
        self.text("Add intentionally", x, 264, 18, WHITE, True)
        self.para("Use Choose photos for explicit selection. Library options also lets you authorize an event folder and its subfolders, then rescan it manually.",
                  x, 294, 283, 11.5, MUTED, 16)
        self.text("Browse the grid", x, 375, 18, WHITE, True)
        self.para("Tap Search or use All, Tagged, and Check filters. Open a thumbnail to inspect it or use Tag or correct people. Close details to return to your place.",
                  x, 405, 283, 11.5, MUTED, 16)
        self.text("Pick your view", x, 486, 18, WHITE, True)
        self.para("On my phone is your selection. Of me has For you, Uncertain, Confirmed, and All previews. Search by filename or friend. Requested holds your downloads.",
                  x, 516, 283, 11.5, MUTED, 16)
        self.callout("Previews are shared. Originals are approved.",
                     "Selection allows verified event friends to see small previews, including photos without a match. Each original needs owner approval and may include camera location metadata.",
                     MARGIN, 699, 519, 82, LIME)
        self.end()

    def recognition_page(self):
        self.header("04 / Find yourself", "A suggestion.<br/>You make the call.",
                    "Real face matching runs on the phone holding the selected photos.", "recognition")
        self.screenshot("match", 357, 215, 200, 430, "Actual app detail with licensed test media. Suggestions are not proof of identity.")
        self.pill("YOU  >  CHOOSE REFERENCE SELFIES", MARGIN, 216, TEAL, False, 8)
        self.para("Choose 1 to 5 clear selfies with only you in each. Then enable face-reference sharing for this event if you want friends' phones to look for you.",
                  MARGIN, 257, 293, 11.5, MUTED, 16)
        self.box(MARGIN, 338, 294, 93)
        self.photo_art(51, 352, 57, 55, TEAL, 1)
        self.text("YOU", 68, 414, 7, TEAL, True)
        self.text("face references", 116, 358, 8, WHITE, True)
        self.arrow(117, 385, 204, 385, LIME)
        self.photo_art(216, 352, 99, 55, LIME, 3)
        self.text("FRIEND'S PHOTOS", 229, 414, 7, LIME, True)
        self.para("References are sensitive; selfie images are not sent. To scan your own selected photos after enrollment, tap Find me in my photos in Of me.",
                  53, 445, 276, 10.5, MUTED, 15)
        rows = [("Suggested", "Looks similar. Inspect the photo.", LIME),
                ("Please check", "Uncertain. Low light or blur can confuse it.", CORAL),
                ("No face match", "Browse all previews to catch missed photos.", TEAL)]
        for i, (title, body, color) in enumerate(rows):
            self.text(title, MARGIN, 522 + i * 50, 11, color, True)
            self.para(body, MARGIN, 540 + i * 50, 292, 10.5, MUTED, 14)
        self.callout("That's me / Not me",
                     "Confirm correct suggestions and reject wrong ones. Open All previews to review misses and manually mark your photos. A match never grants access or approves a download.",
                     MARGIN, 696, 519, 85, LIME)
        self.end()

    def transfer_page(self):
        self.header("05 / Take it home", "Ask. Approve. Keep.",
                    "An original moves only after its owner says yes. Both phones need to stay connected.", "transfer")
        self.pill("OWNER'S PHONE", 57, 207, CORAL, False)
        self.pill("YOUR PHONE", 332, 207, LIME, False)
        self.screenshot("approval", 57, 244, 205, 346, "Actual app UI. Seeded test requests show the owner's approval controls.")
        self.screenshot("requested", 332, 244, 205, 346, "Actual app UI with synthetic test records. This illustrates the collection, not a radio transfer.")
        self.arrow(270, 416, 322, 416, LIME, 3)
        points = [("1", "Request original", "Open a friend's photo in Of me. The request details open next."),
                  ("2", "Approve original", "Owner opens the Photos approval alert or Requested."),
                  ("3", "Open saved photo", "After completion, open it from Requested.")]
        for i, (num, title, body) in enumerate(points):
            x = MARGIN + i * 176
            self.number(num, x, 644, LIME)
            self.text(title, x, 682, 11, WHITE, True)
            self.para(body, x, 704, 160, 10, MUTED, 14)
        self.box(MARGIN, 756, 519, 28, PANEL_LIGHT, 8)
        self.text("GALLERY DESTINATION   Pictures/ThatsMyFace/Requested", 50, 763, 10, TEAL, True)
        self.end()

    def happy_path_page(self):
        self.header("06 / Your first test run", "Two phones.<br/>One complete round trip.",
                    "Phone A owns the test photos. Phone B is in some of them. Then swap roles.", "happy")
        rows = [
            ("Set up the same event", "Use different nicknames. A creates the event; B scans A's invitation.", "Both show the same event. Relaunching preserves local setup."),
            ("Select + enroll", "A selects six authorized photos. B adds clear selfies and opts in for the event.", "Only selected photos belong to the event. Unrelated gallery images are absent."),
            ("Verify + browse", "Start sharing, compare the digits, and accept on both phones.", "One live friend entry. B sees A's previews. Try the All previews filter."),
            ("Review the matches", "Check correct, wrong, uncertain, group, and no-face photos. Confirm or reject.", "Decisions stick after relaunch. Record mistakes and misses. A suggestion can be wrong."),
            ("Ask, then approve", "B requests one original. Before A approves, inspect both phones. A approves.", "No original arrives before approval. After approval, progress reaches a saved copy."),
            ("Find it + repeat", "B opens Requested and the gallery Requested album. Check the saved copy.", "The original opens. Retrying does not add a second saved copy. A's source remains."),
        ]
        for i, (title, action, expected) in enumerate(rows):
            y = 209 + i * 89
            self.box(MARGIN, y, 519, 79)
            self.check("happy_" + str(i + 1), 52, y + 14, title)
            self.text(f"{i + 1:02d}  {title}", 79, y + 11, 11.5, LIME, True)
            self.para(action, 79, y + 31, 218, 9.5, WHITE, 12.5)
            self.line(311, y + 14, 311, y + 65)
            self.para(expected, 325, y + 13, 215, 9.5, MUTED, 13)
        self.para("Want a stronger integrity check? Compare original file size and SHA-256 on consenting test devices. A visual match alone does not prove identical bytes.",
                  MARGIN, 754, 519, 10, MUTED, 14)
        self.end()

    def recovery_page(self):
        self.header("07 / The rough edges", "Break the connection.<br/>Keep the trust.",
                    "Use expendable test copies for permission and deletion checks. Leave source originals untouched.", "recovery")
        cases = [
            ("Reject a pairing", "Reject the verification on one phone. Also try a separate event with the same name.", "No private previews or originals before both peers verify in the same invited event.", TEAL),
            ("Decline an original", "Request a photo, then choose Decline request on the owner phone.", "The request is declined. No original is saved on the receiver.", CORAL),
            ("Disconnect + retry", "Lock the source mid-transfer. Reconnect and verify. Test retry from the receiver, then from the owner.", "Waiting or failure is honest. Retry finishes only when the owner returns and permits it.", TEAL),
            ("Try the same request twice", "Repeat the request or relaunch after a transfer. Use Check saved copy.", "One intact received file remains. A missing received copy needs fresh approval to replace.", LIME),
            ("Remove selected access", "Revoke access to a disposable selected test file, or move that test copy. Refresh the event.", "The file cannot be sent. Reselect it to restore access; unrelated photos stay untouched.", CORAL),
            ("Go without internet", "After setup, turn mobile data off and disconnect internet Wi-Fi. Keep Wi-Fi and Bluetooth enabled.", "Test discovery, matching, and an approved original. Record the result for both phones.", LIME),
        ]
        for i, (title, action, expected, accent) in enumerate(cases):
            x, y = MARGIN + (i % 2) * 267, 212 + (i // 2) * 173
            self.box(x, y, 252, 160)
            self.check("resilience_" + str(i + 1), x + 14, y + 14, title)
            self.text(title, x + 40, y + 13, 11, accent, True)
            self.para(action, x + 14, y + 43, 223, 10, WHITE, 13.5)
            self.para("<b>EXPECT</b>  " + expected, x + 14, y + 100, 223, 9.5, MUTED, 12.5)
        self.para("Also try: 100+ photos, large text, TalkBack, landscape, denied permissions, and low storage. Offline capability does not mean the phone or SDK never makes network requests.",
                  MARGIN, 746, 519, 10, MUTED, 14)
        self.end()

    def privacy_page(self):
        self.header("08 / Leave on your terms", "Your photos.<br/>Your call.",
                    "No account or app cloud photo library. You choose what gets shared and what stays local.", "privacy")
        self.screenshot("profile", 357, 215, 200, 424, "Actual You screen with a local test profile. No private face data is shown.")
        controls = [
            ("STOP THIS SESSION", "Tap Stop sharing in Friends. Locking the phone or leaving the app also stops it.", TEAL),
            ("STOP FUTURE ACCESS", "Open Manage event access in Friends, then Remove future access. Downloaded copies cannot be recalled.", LIME),
            ("REMOVE FACE DATA", "In You, switch off event reference sharing or choose Delete local face data. Previously shared data on offline phones cannot be recalled.", TEAL),
            ("RESET THE APP", "Delete local app data removes profile, events, references, requests, and feedback. Source photos and received gallery copies remain.", CORAL),
        ]
        for i, (title, body, color) in enumerate(controls):
            y = 216 + i * 108
            self.text(title, MARGIN, y, 10, color, True)
            self.para(body, MARGIN, y + 23, 293, 11, MUTED, 15)
        self.callout("Tell us how the night went",
                     "In You, tap Leave feedback, write a short note, and Save feedback. Export saved notes only when you choose. Include the problem and what you expected; leave out private photos, faces, names, and invitation codes.",
                     MARGIN, 685, 519, 96, LIME)
        self.end()

    def report_page(self):
        self.header("09 / Keep a useful record", "What worked?<br/>What needs another try?",
                    "Save this PDF after checking its boxes. Short observations are more useful than private screenshots.", "report")
        self.result_field("phone_a", "PHONE A / ANDROID", MARGIN, 207, 252)
        self.result_field("phone_b", "PHONE B / ANDROID", 305, 207, 252)
        self.result_field("build_date", "APK VERSION / TEST DATE", MARGIN, 271, 252)
        self.result_field("match_counts", "MATCHES: RIGHT / WRONG / MISSED / UNCERTAIN", 305, 271, 252)
        self.result_field("result_note", "RESULT / WHAT YOU EXPECTED / WHAT HAPPENED", MARGIN, 335, 519, 44)
        self.box(MARGIN, 418, 519, 152)
        self.text("WHAT THE EVIDENCE SAYS", 53, 433, 10, LIME, True)
        y = 456
        for prefix, key in [("Automated", "automated"), ("Devices", "device"), ("Still pending", "pending")]:
            h = self.para(f"<b>{prefix}.</b> " + escape(self.evidence[key]), 53, y, 489, 9.2, WHITE, 12)
            y += h + 8
        if y > 568:
            raise ValueError("Evidence summary needs shorter text")
        self.text("IF SOMETHING GETS STUCK", MARGIN, 590, 10, TEAL, True)
        rescues = [
            ("No friend / no photos", "Same QR event? Sharing on both? Codes accepted? Try the All previews filter."),
            ("Awaiting owner approval", "The owner must tap Approve original in Photos > Requested."),
            ("Waiting for your friend", "Bring both apps back, reconnect, verify, then retry the request."),
            ("Access removed / missing", "Reselect the authorized source. A received copy is separate from its source."),
        ]
        for i, (label, answer) in enumerate(rescues):
            y = 614 + i * 33
            self.text(label, MARGIN, y, 9, WHITE, True)
            self.para(answer, 202, y, 355, 9, MUTED, 12)
        self.text("VERSION " + self.evidence["app_version"] + "  /  BUILD " + self.evidence["source_commit"],
                  MARGIN, 759, 8, MUTED)
        self.c.linkURL(self.evidence["validation_url"],
                       (MARGIN, HEIGHT - 788, 305, HEIGHT - 772), relative=0, thickness=0)
        self.text("Full validation record and known limits on GitHub", MARGIN, 774, 8, TEAL)
        self.end()

    def build(self):
        self.cover()
        self.map_page()
        self.invitation_page()
        self.selection_page()
        self.recognition_page()
        self.transfer_page()
        self.happy_path_page()
        self.recovery_page()
        self.privacy_page()
        self.report_page()
        self.c.save()


def render(output: Path, directory: Path):
    import pypdfium2 as pdfium
    from PIL import Image, ImageDraw

    directory.mkdir(parents=True, exist_ok=True)
    document = pdfium.PdfDocument(output)
    document.init_forms()
    thumbnails = []
    for index, page in enumerate(document):
        bitmap = page.render(scale=1.5)
        image = bitmap.to_pil()
        image.save(directory / f"page-{index + 1:02d}.png")
        image.thumbnail((298, 421))
        thumbnails.append(image.copy())
        text = page.get_textpage().get_text_range()
        if len(text.strip()) < 300:
            raise ValueError(f"Page {index + 1} has unexpectedly little extractable text")
        bitmap.close()
    sheet = Image.new("RGB", (5 * 314, 2 * 455), "#DDE4E6")
    draw = ImageDraw.Draw(sheet)
    for index, image in enumerate(thumbnails):
        x, y = (index % 5) * 314 + 8, (index // 5) * 455 + 8
        sheet.paste(image, (x, y))
        draw.text((x, y + 426), f"{index + 1:02d}", fill="#142536")
    sheet.save(directory / "contact-sheet.png")
    document.close()
    print(f"Rendered and text-checked {len(thumbnails)} pages in {directory}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "docs" / "ThatsMyFace-Walkthrough.pdf")
    parser.add_argument("--evidence", type=Path, default=ASSETS / "evidence.json")
    parser.add_argument("--render-dir", type=Path)
    parser.add_argument("--allow-missing", action="store_true", help="Internal draft only; show missing screenshot labels")
    args = parser.parse_args()
    evidence = json.loads(args.evidence.read_text(encoding="utf-8"))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    guide = Guide(args.output, evidence, args.allow_missing)
    guide.build()
    print(f"Created {args.output} ({guide.page_number} pages, {guide.fields} checkboxes)")
    if args.render_dir:
        render(args.output, args.render_dir)


if __name__ == "__main__":
    main()
