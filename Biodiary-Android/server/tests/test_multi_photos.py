"""Run from the workspace with Flask and Pillow installed; only uses temporary data."""
import importlib.util
import gc
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import time
import unittest

from PIL import Image


class MultiplePhotosTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory(prefix=".photo-test-", dir=Path(__file__).resolve().parent)
        os.environ["BIODIARY_DATA_DIR"] = cls.directory.name
        os.environ["BIODIARY_CLOUDFLARE_AUTH_ONLY"] = "0"
        source = Path(__file__).resolve().parents[1] / "biodiary_api.py"
        spec = importlib.util.spec_from_file_location("multi_photo_test_api", source)
        cls.api = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = cls.api
        spec.loader.exec_module(cls.api)
        cls.api.app.config["TESTING"] = True
        cls.client = cls.api.app.test_client()
        with cls.api.connect_db() as connection:
            for account in ("owner", "other"):
                connection.execute("INSERT INTO accounts(id,account_code,login_name,password_hash,display_name,registered_at) VALUES(?,?,?,?,?,?)",
                                   (account, account, account, "unused", account, 1))
                connection.execute("INSERT INTO sessions VALUES(?,?,?,?)",
                                   (cls.api.hash_secret(account), account, 1, int(time.time()) + 3600))
        cls.jpegs = []
        for color in ("green", "blue", "red", "yellow"):
            stream = io.BytesIO()
            Image.new("RGB", (16, 12), color).save(stream, "JPEG")
            cls.jpegs.append(stream.getvalue())

    @classmethod
    def tearDownClass(cls):
        gc.collect()
        cls.directory.cleanup()

    def photo_data(self, url):
        with self.client.get(url, headers={"Authorization": "Bearer owner"}) as response:
            self.assertEqual(response.status_code, 200)
            return response.data

    def upload(self, identifier, count, method="POST", private=False, legacy=False, bad_last=False):
        metadata = dict(id=identifier, name="アオサギ", dateMillis=1000, latitude=35, longitude=139,
                        category="BIRD", shared=False, locationPrivate=private, prefecture="東京都" if private else "")
        if not legacy:
            metadata["photoCount"] = count
        data = {"metadata": json.dumps(metadata)}
        for index in range(count):
            payload = b"invalid" if bad_last and index == count - 1 else self.jpegs[index % 4]
            if private and not bad_last:
                exif = b"Exif\x00\x00private-gps"
                payload = payload[:2] + b"\xff\xe1" + (len(exif) + 2).to_bytes(2, "big") + exif + payload[2:]
            data["photo" if index == 0 else f"photo{index + 1}"] = (io.BytesIO(payload), "photo.jpg")
        path = "/api/v1/observations" + ("/" + identifier if method == "PUT" else "")
        return self.client.open(path, method=method, data=data, headers={"Authorization": "Bearer owner"})

    def test_four_photos_order_and_primary_compatibility(self):
        response = self.upload("four", 4)
        self.assertEqual(response.status_code, 201)
        record = response.json["observation"]
        self.assertEqual(record["photoCount"], 4)
        self.assertEqual(record["photoUrls"][0], record["photoUrl"])
        for index, url in enumerate(record["photoUrls"]):
            self.assertEqual(self.photo_data(url), self.jpegs[index])
            self.assertEqual(self.client.get(url, headers={"Authorization": "Bearer other"}).status_code, 404)
            self.assertEqual(self.client.get(url).status_code, 401)

    def test_limit_and_failed_upload_cleanup(self):
        before = set(self.api.PHOTO_DIR.iterdir())
        self.assertEqual(self.upload("too-many", 5).status_code, 400)
        self.assertEqual(self.upload("bad-photo", 4, bad_last=True).status_code, 400)
        self.assertEqual(set(self.api.PHOTO_DIR.iterdir()), before)

    def test_edit_shrink_delete_and_legacy_compatibility(self):
        record = self.upload("edit", 4).json["observation"]
        old_extra = self.photo_data(record["photoUrls"][1])
        response = self.upload("edit", 1, "PUT", legacy=True)
        self.assertEqual(response.json["observation"]["photoCount"], 4)
        self.assertEqual(self.photo_data(record["photoUrls"][1]), old_extra)
        self.assertEqual(self.upload("edit", 2, "PUT").json["observation"]["photoCount"], 2)
        self.assertEqual(self.client.get("/api/v1/observations/edit/photo/3", headers={"Authorization": "Bearer owner"}).status_code, 404)
        with self.api.connect_db() as connection:
            row = connection.execute("SELECT * FROM observations WHERE id='edit'").fetchone()
            filenames = [photo["filename"] for photo in self.api.row_photos(row)]
        self.assertEqual(self.client.delete("/api/v1/observations/edit", headers={"Authorization": "Bearer owner"}).status_code, 200)
        self.assertTrue(all(not (self.api.PHOTO_DIR / name).exists() for name in filenames))
        self.assertEqual(self.upload("single", 1, legacy=True).json["observation"]["photoCount"], 1)

    def test_all_private_photos_remove_metadata(self):
        response = self.upload("private", 4, private=True)
        self.assertEqual(response.status_code, 201)
        record = response.json["observation"]
        self.assertEqual(record["latitude"], 35.6895)
        for url in record["photoUrls"]:
            data = self.photo_data(url)
            self.assertNotIn(b"private-gps", data)
            Image.open(io.BytesIO(data)).load()

    def test_shared_record_is_read_only_for_other_accounts(self):
        record = self.upload("permission", 4).json["observation"]
        record["shared"] = True
        path = "/api/v1/observations/permission"
        response = self.client.put(path, json=record, headers={"Authorization": "Bearer owner"})
        self.assertEqual(response.status_code, 200)
        for index, url in enumerate(record["photoUrls"]):
            with self.client.get(url, headers={"Authorization": "Bearer other"}) as response:
                self.assertEqual(response.status_code, 200)
                self.assertEqual(response.data, self.jpegs[index])
        record["name"] = "他人による書き換え"
        record["shared"] = False
        other = {"Authorization": "Bearer other"}
        self.assertEqual(self.client.put(path, json=record, headers=other).status_code, 403)
        self.assertEqual(self.client.delete(path, headers=other).status_code, 403)
        replacement = {"metadata": json.dumps({**record, "photoCount": 1}),
                       "photo": (io.BytesIO(self.jpegs[1]), "replacement.jpg")}
        self.assertEqual(self.client.put(path, data=replacement, headers=other).status_code, 403)
        unchanged = self.client.get(path, headers={"Authorization": "Bearer owner"}).json["observation"]
        self.assertEqual(unchanged["name"], "アオサギ")
        self.assertTrue(unchanged["shared"])
        self.assertEqual(unchanged["photoCount"], 4)
        for index, url in enumerate(unchanged["photoUrls"]):
            self.assertEqual(self.photo_data(url), self.jpegs[index])

    def test_genre_is_required(self):
        metadata = dict(id="genre", name="アオサギ", dateMillis=1000, latitude=35, longitude=139)
        for category in (None, "", "unknown"):
            body = {**metadata, "category": category}
            self.assertEqual(self.client.post("/api/v1/observations", json=body,
                                             headers={"Authorization": "Bearer owner"}).status_code, 400)


if __name__ == "__main__":
    unittest.main()
