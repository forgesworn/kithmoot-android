# Recoverable native room creation

`NativeRoomCreation` coordinates local stores on IO before any route opens.
It first persists an encrypted intent with the prepared non-host SavedRoom,
original local owner/device capability, public source reference and owner
credential. The intent contains no root signer and cannot grant admission or
hosting. It then transfers the sole new signer into the independent source,
installs the non-signing SavedRoom reference from that actual suspended source,
saves the room and clears the intent last.

A failed operation poisons that coordinator until another exclusive owner
inspects the real stores. Cold recovery can finish a committed source/save;
it never generates a replacement signer or identity. Missing/corrupt source
state leaves the unfinished intent in place. Recovery of a lost save return
keeps existing metadata, lifecycle and retry debt instead of overwriting the
saved room with the earlier draft. A conflicting saved owner/reference or
legacy host refuses. No source/receiver readiness is inferred from the intent.

The Android factory uses a separate no-backup encrypted intent alias and the
actual NativeKeeperVault. The pure-JVM qualification exercises independent
intent/source/repository owners at before/after commit and delete failures,
plus conflicting/corrupt records and duplicate ownership. That qualification
does not cover Android keystore, real PID death or the ViewModel flow.

The component still needs explicit foreground creation/unfinished recovery UI,
controller attachment and qualified abandonment/forget cleanup. It offers no
automatic reset or discard action. Existing legacy hosting remains separate
until its explicit authority transfer is designed and measured. Public room
welcome/metadata publication must use the selected guarded authority owner;
creation does not publish anything.
