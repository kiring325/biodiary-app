"""コミュニティごとに共有記録・メンバー・いいね・コメントが分かれることを確かめる。

Flask と Pillow を入れた環境で、`python3 -m unittest discover -s tests` で実行する。一時フォルダのデータだけを使う。
"""
import base64
import gc
import hashlib
import hmac
import importlib.util
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import time
import unittest

from PIL import Image

SECRET = "test-secret"


def identity(account_code, login_name, community_id=None, member=True):
    claims = {"accountId": account_code, "loginName": login_name, "displayName": login_name, "message": "",
              "registeredAt": 1, "communityMember": member, "expiresAt": int(time.time()) + 60}
    if community_id is not None:
        claims["communityId"] = community_id
    payload = base64.urlsafe_b64encode(json.dumps(claims).encode()).rstrip(b"=").decode()
    signature = base64.urlsafe_b64encode(
        hmac.new(SECRET.encode(), payload.encode(), hashlib.sha256).digest()).rstrip(b"=").decode()
    return {"X-Biodiary-Identity": payload + "." + signature}


# 本番メンバー2人と、面接用コミュニティの2人
ALICE = ("BD-AAAAAAAA0001", "alice", "default")
BOB = ("BD-AAAAAAAA0002", "bob", "default")
CAROL = ("BD-BBBBBBBB0001", "carol", "interview")
DAVE = ("BD-BBBBBBBB0002", "dave", "interview")


class CommunitiesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory(prefix=".community-test-", dir=Path(__file__).resolve().parent)
        os.environ["BIODIARY_DATA_DIR"] = cls.directory.name
        os.environ["BIODIARY_CLOUDFLARE_IDENTITY_SECRET"] = SECRET
        os.environ["BIODIARY_CLOUDFLARE_AUTH_ONLY"] = "1"
        source = Path(__file__).resolve().parents[1] / "biodiary_api.py"
        spec = importlib.util.spec_from_file_location("community_test_api", source)
        cls.api = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = cls.api
        spec.loader.exec_module(cls.api)
        cls.api.app.config["TESTING"] = True
        cls.client = cls.api.app.test_client()
        stream = io.BytesIO()
        Image.new("RGB", (16, 12), "green").save(stream, "JPEG")
        cls.jpeg = stream.getvalue()
        cls.post(ALICE, "alice-post")
        cls.post(CAROL, "carol-post")

    @classmethod
    def tearDownClass(cls):
        gc.collect()
        cls.directory.cleanup()

    @classmethod
    def headers(cls, who):
        return identity(*who)

    @classmethod
    def post(cls, who, identifier):
        metadata = dict(id=identifier, name="アオサギ", dateMillis=1000, latitude=35, longitude=139,
                        category="BIRD", shared=True, photoCount=1)
        data = {"metadata": json.dumps(metadata), "photo": (io.BytesIO(cls.jpeg), "a.jpg", "image/jpeg")}
        response = cls.client.post("/api/v1/observations", data=data, headers=cls.headers(who),
                                   content_type="multipart/form-data")
        assert response.status_code == 201, response.get_json()

    def visible_ids(self, who):
        response = self.client.get("/api/v1/observations?since=0", headers=self.headers(who))
        self.assertEqual(response.status_code, 200)
        return set(response.get_json()["visibleIds"])

    def test_shared_posts_stay_in_their_community(self):
        self.assertEqual(self.visible_ids(BOB), {"alice-post"})
        self.assertEqual(self.visible_ids(DAVE), {"carol-post"})
        self.assertEqual(self.visible_ids(ALICE), {"alice-post"})

    def test_cannot_open_other_community_post(self):
        for path in ("/api/v1/observations/alice-post", "/api/v1/observations/alice-post/photo",
                     "/api/v1/observations/alice-post/comments", "/api/v1/observations/alice-post/likes"):
            response = self.client.get(path, headers=self.headers(DAVE))
            self.assertEqual(response.status_code, 404, path)
        response = self.client.put("/api/v1/observations/alice-post/likes", headers=self.headers(DAVE))
        self.assertEqual(response.status_code, 404)
        self.assertEqual(self.client.get("/api/v1/observations/alice-post", headers=self.headers(BOB)).status_code, 200)

    def test_members_and_profiles_are_separated(self):
        response = self.client.get("/api/v1/community/members", headers=self.headers(DAVE))
        self.assertEqual({m["loginName"] for m in response.get_json()["members"]}, {"carol", "dave"})
        response = self.client.get(f"/api/v1/community/members/{ALICE[0]}/observations", headers=self.headers(DAVE))
        self.assertEqual(response.get_json()["total"], 0)
        response = self.client.get(f"/api/v1/community/members/{CAROL[0]}/observations", headers=self.headers(DAVE))
        self.assertEqual(response.get_json()["total"], 1)

    def test_reactions_and_notifications_within_community(self):
        response = self.client.put("/api/v1/observations/carol-post/likes", headers=self.headers(DAVE))
        self.assertEqual(response.status_code, 200)
        summary = self.client.get("/api/v1/observations/reactions/summary", headers=self.headers(DAVE)).get_json()
        self.assertEqual({item["id"] for item in summary["reactions"]}, {"carol-post"})
        notes = self.client.get("/api/v1/notifications", headers=self.headers(CAROL)).get_json()
        self.assertEqual([n["observationId"] for n in notes["notifications"]], ["carol-post"])

    def test_old_worker_without_community_id_means_default(self):
        old = identity("BD-AAAAAAAA0003", "erin")  # communityId なし
        response = self.client.get("/api/v1/observations?since=0", headers=old)
        self.assertEqual(set(response.get_json()["visibleIds"]), {"alice-post"})

    def test_bad_community_id_is_rejected(self):
        response = self.client.get("/api/v1/observations", headers=identity("BD-AAAAAAAA0004", "frank", "Bad ID"))
        self.assertEqual(response.status_code, 401)


if __name__ == "__main__":
    unittest.main()
