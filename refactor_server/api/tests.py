from django.test import TestCase
from django.urls import reverse


class ExtractMethodPlanTests(TestCase):
    def test_extract_method_plan_get_returns_usage_hint(self):
        response = self.client.get(reverse("extract_method_plan"))
        self.assertEqual(response.status_code, 200)
        payload = response.json()
        self.assertEqual(payload["status"], "ok")
        self.assertIn("Use POST with JSON", payload["message"])

    def test_extract_method_plan_returns_expected_schema(self):
        response = self.client.post(
            reverse("extract_method_plan"),
            data='{"workspacePath":"/tmp/ws","client":"eclipse-refactor-plugin","focusProject":"camel","focusClassName":"ExportQuarkus","focusClassId":"camel_1"}',
            content_type="application/json",
        )

        self.assertEqual(response.status_code, 200)
        payload = response.json()

        self.assertEqual(payload["status"], "success")
        self.assertIn("targetRelativePath", payload)
        self.assertIn("methodName", payload)
        self.assertIn("ranges", payload)
        self.assertIn("planningContext", payload)
        self.assertIsInstance(payload["ranges"], list)
        self.assertEqual(payload["planningContext"]["focusProject"], "camel")
        self.assertEqual(payload["planningContext"]["focusClassName"], "ExportQuarkus")
        self.assertEqual(payload["planningContext"]["focusClassId"], "camel_1")
