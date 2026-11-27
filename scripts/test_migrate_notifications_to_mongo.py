import unittest
from datetime import datetime, timezone

from migrate_notifications_to_mongo import normalize_document, same_documents


class NotificationMigrationTest(unittest.TestCase):
    def test_notification_preserves_string_ids_and_read_state(self):
        source = {
            "_id": "000000000000000000000001",
            "templateId": "ntpl-ticket-confirmation-email",
            "readStatus": True,
            "userId": "ADMIN",
            "channel": "EMAIL",
            "status": "SENT",
            "eventId": "  ",
            "createdAt": datetime(2026, 11, 25, 9, 15, 1, 123456),
        }

        self.assertEqual(
            normalize_document(source, timezone.utc),
            {
                "_id": "000000000000000000000001",
                "templateId": "ntpl-ticket-confirmation-email",
                "readStatus": True,
                "userId": "ADMIN",
                "channel": "EMAIL",
                "status": "SENT",
                "priority": "NORMAL",
                "createdAt": datetime(2026, 11, 25, 9, 15, 1, 123000, timezone.utc),
            },
        )

    def test_reconciliation_detects_changed_template_content(self):
        source = [{"_id": "custom-template", "bodyTemplate": "HaloTicket"}]
        changed = [{"_id": "custom-template", "bodyTemplate": "Default"}]

        self.assertFalse(same_documents(source, changed))

    def test_reconciliation_detects_missing_document(self):
        self.assertFalse(same_documents([{"_id": "notification-1"}], []))


if __name__ == "__main__":
    unittest.main()
