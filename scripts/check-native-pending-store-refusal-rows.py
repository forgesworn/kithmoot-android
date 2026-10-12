"""Generated malformed-row checks, separate from actual emulator acceptance."""
import unittest
from native_pending_store_refusal import pending_refusal_measurement

WINDOWS = ("committed-source-before-offer", "charged-original-before-offer", "offered-before-index",
           "index-committed-before-source-acknowledgement", "reference-installed-before-subscription-switch")


def rows_for(window):
    i = WINDOWS.index(window)
    faults = [(target, fault) for target in ("SOURCE", "RECEIVER", "COURIER", "INDEX") for fault in ("MISSING", "CORRUPT")]
    faults += [("INDEX", fault) for fault in ("OWNER_DEVICE", "ROUTE_PINS", "INVITATION")]
    return [dict(window=window, stage="NOTICE_ARCHIVED" if i == 3 else "INDEX_VERIFIED" if i == 4 else "ORIGINALS_RETAINED",
                 target=target, fault=fault, originalId="11" * 32, originalCreatedAt="100", welcomeId="22" * 32,
                 welcomeCreatedAt="100", originalBytes="426", attempts="0" if i == 0 else "1", offered="1" if i >= 2 else "0",
                 chargedBytes="0" if i == 0 else "426", epoch="1", devices="3", sourceGeneration="0", proposedGeneration="1",
                 indexGeneration="1" if i >= 3 else "0", newRadios="0", newSubscriptions="0", newOffers="0", relayRequests="0",
                 filesUnchanged="true", keysUnchanged="true", cleanupVerified="true") for target, fault in faults]


def lines(rows):
    return ["NATIVE_PENDING_STORE_REFUSAL " + " ".join(key + "=" + value for key, value in row.items()) for row in rows]


class PendingRefusalRowsTest(unittest.TestCase):
    def test_five_stages_with_eleven_unique_faults(self):
        for window in WINDOWS:
            with self.subTest(window=window):
                capture = lines(rows_for(window))
                capture[0] = "dev.forgesworn.kithmoot.epoch.NativeReplacementRestartTest:INSTRUMENTATION_STATUS: stream=" + capture[0]
                self.assertEqual(11, len(pending_refusal_measurement(capture, window)))

    def test_every_missing_field_and_unsafe_changed_assertion_refuses(self):
        refused = 0
        for window in WINDOWS:
            reference = rows_for(window)
            for name in reference[0]:
                with self.subTest(window=window, missing=name):
                    rows = rows_for(window); del rows[0][name]
                    with self.assertRaises(RuntimeError): pending_refusal_measurement(lines(rows), window)
                    refused += 1
            changes = dict(window="wrong-window", stage="COMPLETE", target="OTHER", fault="OTHER", originalId="33" * 32,
                           originalCreatedAt="0100", welcomeId="11" * 32, welcomeCreatedAt="101", originalBytes="16385",
                           attempts="2", offered="2", chargedBytes="999", epoch="0", devices="2", sourceGeneration="1",
                           proposedGeneration="2", indexGeneration="2", newRadios="1", newSubscriptions="1", newOffers="1",
                           relayRequests="1", filesUnchanged="false", keysUnchanged="false", cleanupVerified="false")
            for name, value in changes.items():
                with self.subTest(window=window, changed=name):
                    rows = rows_for(window); rows[-1][name] = value
                    with self.assertRaises(RuntimeError): pending_refusal_measurement(lines(rows), window)
                    refused += 1
            for capture in (lines(reference[:-1]), lines(reference + [reference[0]]),
                            lines(reference[:-1] + [reference[0]]), lines(reference) + [lines(reference)[0] + " epoch=1"]):
                with self.assertRaises(RuntimeError): pending_refusal_measurement(capture, window)
                refused += 1
        print(f"GENERATED_PENDING_REFUSAL_ROWS accepted=5 refused={refused} emulatorEvidence=false")


if __name__ == "__main__":
    unittest.main()
