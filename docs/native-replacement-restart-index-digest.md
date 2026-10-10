# Replacement restart invitation digest assertion

Mesh-kit precode **633cd47**, `spec/native-replacement-restart-index-digest.md`,
precedes this correction. Actual c44bc5a CI 38081295486 fails at the fourth
replacement recovery's old-link inequality. All 144 ordinary checks/45 groups,
six retired-store rows, eight retained drivers and three replacement drivers
pass first; verify and both signing jobs pass. Its workflow remains failed.

The actual index checkpoint holds the old link in the first three windows and
the proposed link in the final two windows. Requiring that checkpoint digest
always differ from the completed index incorrectly rejects the fourth window,
where the proposed link is already committed before source acknowledgement.

The corrected fixture checks the exact cold source's previous/proposed digests
are distinct, binds the checkpoint's actual index digest to the correct window,
then requires the completed index to equal the exact proposed link and differ
from the previous link in all five windows. Existing full source/index digests,
reference checks, sharing, ownership, event/epoch/audience/budget/custody,
listener refusal and fresh admission/chat assertions stay intact. Production
behaviour, checkpoint preparation, timeouts and offers are unchanged.

This head requires fresh changed-fixture compilation and its own complete
four-job, 144/45/six-row/thirteen-driver gate. Actual c44's three completed new
profiles cannot substitute for the final two or qualify a successor. The
pending-store fixture successor needs its additional five profiles/55 rows and
full changed-head base gate separately. No physical port, firmware, channel,
MQTT setting, public relay, personal signer or radio lab key is operated on.
