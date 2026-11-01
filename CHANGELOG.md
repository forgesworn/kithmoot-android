## 0.6.132 (1 November 2026)

- Calls work again for people on networks that block direct connections. A call with no relay of your own asks ForgeSworn's call relay for a short-lived login, and carries on without it if that fails. Hide my IP address on calls uses it too.
- Calls ask Google's public STUN server and then Cloudflare's, unless you set your own.
- Devices on the same network send each other their local addresses again, so a call between them connects directly. The address behind a reflexive or relay candidate is still hidden.
- ForgeSworn's relay, relay.trotters.cc, is the first default Nostr relay again.
- Start call and Join call switch your microphone on, and ask for microphone permission if needed. If you refuse, you are in the call with the microphone off.

## 0.6.131 (1 November 2026)

- An unsent message stops being resent by itself a day after it was written. It stays in the box as not sent, and Retry sends it as a new message. A message whose access ran out before then also waits for Retry, instead of saying the room changed its key.
- Calls start with fewer messages to the room's relays: the first offer and answer carry the network addresses found in their first fifth of a second.
- A call between a browser and this phone could end with no video when two offers crossed on a slow relay. Fixed.
- Retry pressed while the phone was already trying to send a message by itself was ignored. It now sends.
- After a relay reconnects, the phone asks it only for chat from the last day or so, not the last 30 days, and the private chat list no longer asks again for profiles it already has.

## 0.6.130 (31 October 2026)

- Settings splits Connections into Calls and Relays. Calls opens with one line saying how your calls connect, then the call relay on this phone, your Spry node, a relay server and public call relays, each on its own page. Hide my IP address now lives on Calls, and Privacy opens it.
- The call relay on this phone is offered to everyone. It is off until you turn it on, and carries calls for your own rooms when people cannot connect directly.
- Relay server, Spry and public relay settings are pages instead of dialogs, and Remove asks first.

## 0.6.129 (31 October 2026)

- A device that falls silent now stays in a room's list for 100 seconds rather than 75, matching the desktop and web app. This makes room for a slower heartbeat in a later version, which will send fewer messages to the public relays a room uses.

## 0.6.128 (30 October 2026)

- The phone is gentler with the public Nostr relays it uses. When a relay says it is busy or refuses the phone, it waits before trying again instead of retrying straight away; unsent messages stop retrying after eight tries and say so; and background rooms share far fewer subscriptions.
- New phones start on relay.primal.net, relay.ditto.pub and offchain.pub, after nostr.oxtr.dev stopped answering some home connections.
- Groundwork for a call relay on this phone, kept off in this release.

## 0.6.127 — 30 October 2026

- When a member asks this phone to test their built-in call relay from outside, the phone now reports a working relay as working. Before, it often reported a working relay as unreachable, because it trusted status flags that can lag behind the traffic.

## 0.6.126 — 30 October 2026

- Your messages show two ticks once another person's device has received them, with "Delivered to NAME" or "Delivered to 3"; tap for who. It never says whether they read it. Settings, Privacy, Delivery can stop this phone sending confirmations; quiet chats never send them.
- With Hide my IP on, the phone now asks before using any relay another member runs, not only built-in ones, and ignores copies of a relay passed on by someone other than the person who runs it.

## 0.6.125 — 29 October 2026

- When another member shares their own built-in call relay in a room, the phone can use it for calls. With Hide my IP on, pressing Join first asks "Use NAME's relay?" ("NAME will see your address; others won't."), with Use relay or Not now; the answer is kept on this phone for that person. Not now keeps the call off that relay rather than connecting directly.
- A room host can ask this phone to test their built-in relay from outside their network. The phone answers with pass or fail and the stage that failed, never an address. It only answers the person who shares that relay, and declines when Hide my IP has not allowed them.

## 0.6.124 — 29 October 2026

- A message you send shows a tick once a relay has confirmed it kept it, as on the web and desktop. A clock shows while the relays are still being asked. If a relay accepted it and did not keep it, the message says **Not kept** with **Retry**, which offers the same message again. The tick stays the same after a restart. In quiet rooms the tick means the drop carrying the message was accepted.
- Reads the delivery confirmation format the web and desktop clients now send, checked against their published vectors. The phone does not yet send or show confirmations; that comes next.

## 0.6.123 — 29 October 2026

- With Spry paired, your own calls use your own call relay in every room. It is shared with everyone automatically only in rooms you made on this phone; in other rooms, Room info, Privacy and connection has a "Share my call relay here" switch, kept on this phone. The switch is not shown while a call relay you entered yourself is set, since that relay is used instead. Turning it off asks Spry to drop the shared access, which ends within five minutes.
- Where your relay is shared, the app registers the room's members' devices with your Spry and asks it to accept only those, once every device present supports it. Agents are never registered. People you blocked on this phone are registered like anyone else, since a block is never revealed.
- Members using a shared relay help newcomers in: a member whose own access is fresh vouches for each device that joins after them, once, after a short random wait.
- Each device announces its own Spry key for the room in its presence, as the web app does.
- When the call relay someone else shared is not available, the call says so instead of pointing at a pairing you do not have. With Hide my IP on, the call waits a few seconds for the room's owner to let you in and refuses rather than going direct.
- Public call relays can show their operator's own privacy notice link with its hostname. KithMoot never opens or stores it; it opens in your browser only when you tap it.
- Other people on a call no longer see your device's local network addresses (the ones your Wi-Fi, VPN or mesh gives it). Calls between devices on the same network now go through your router or a call relay rather than straight across the local network. Hide IP on calls is unchanged.
- Send any file, not only pictures: **Add photo or file** beside the message box opens the system file picker for any type, up to 256 MiB as the web and desktop clients allow, or a public server's own smaller limit where it has one. The file is encrypted on the phone a megabyte at a time and only the sealed envelope is uploaded, to the place you chose under Where files go, with the same one-time notice and the same limits on anonymous rooms as before. **Stop upload** ends one in progress. A room that keeps its files on its group's own storage takes any file there too, and a received file is read from there.
- Show a received file that is not a picture as a compact item with its name, download size and type. Tapping it offers **Save**, to a place you choose, or **Open with** another app. Nothing downloads until you choose, the file is checked against the message before it is opened, and its name is cleaned, in the extension too, so it cannot disguise itself. Android app packages can only be saved. Pictures, recordings and the text document a long paste becomes open as they did.
- Read a shared file's details exactly as the web client does, now checked against every published `chatAttachment` vector and a file the web client wrote. A type that carries parameters, such as `text/plain;charset=utf-8`, is no longer kept as a hint, as in the web client.

## 0.6.122 — 28 October 2026

- A room left open for more than a day keeps sending. Your device's access to
  the room is now renewed while the room stays open, by the clock rather than
  a timer that stops while the phone sleeps: when you come back to the app,
  when the network returns, while the room is open, and just before a message
  goes. A key kept on this phone renews without asking. A signer app is asked
  quietly, and shows itself only when you are in the room and press Send or
  come back to it with little time left, once however many messages are
  waiting.
- A message never goes out under access that has run out. It stays in the
  message box until access is renewed, then sends. If your signer refuses or
  does not answer, the box says so in plain words, and Send tries again.

## 0.6.116 — 26 October 2026

- Restore bundled on-device background segmentation: keep the person visible
  over the sea scenes and animated fish, without Google Play or model downloads.
- Keep MediaPipe and QR usage-reporting transports excluded and replaced with
  no-ops; verify the reviewed SDK/model pins and logger path in minified releases.
- Keep fish rendering when segmentation is unavailable and reject invalid masks
  or results from an earlier camera session without exposing the raw frame.

## 0.6.115

- Images and GIFs now decode in an isolated process, away from your rooms and account keys.
- Attachment checks are stricter, and closing or retracting an image is safer.
- Physical GrapheneOS media, memory, active-call and accessibility checks remain separate acceptance gates.

## 0.6.114

- Keep WebRTC native startup working in minified release builds, fixing a crash when opening a room.

## 0.6.113

- Choose Loudspeaker on or off from a call's More menu; a connected headset keeps priority. The choice stays on this device.
- Opening a conversation follows its latest loaded messages. Jump to the latest sits below the messages rather than covering them.
- The account button uses the public Nostr profile picture when enabled, and its menu actions are larger.
- Update prompts support two or three bullets from signed release metadata. Older installed versions gain that display after updating.
- Optionally find public call relays under Settings, Connections. Discovery is off by default and asks your existing Nostr read relays for operators' signed adverts; KithMoot supplies no operator list or directory.
- Select and check one relay without camera or microphone use. The check's login can be reused by the next call; public relay logins are forgotten when that call ends.
- Your own call relay and Spry retain priority. Hide IP on calls never falls back direct; a public relay that cannot be used produces a relay-lost notice.
- Physical GrapheneOS TLS, DNS, relay-only and dialog checks remain separate acceptance gates.

- The release APK is about 9 MB (12%) smaller, with unused code and resources removed.
- Busy chats avoid repeatedly deriving the same rows, invitations and reaction lookups.
- Duplicate stored relay events are discarded before signature verification, and QR sharing batches its pixel updates.
- Opening or reading rooms and roster updates no longer refresh unchanged chat notifications.

## 0.6.111

- Pair Spry once, then use the normal Call button in a conversation or group. KithMoot obtains and renews call access automatically, without entering relay usernames or passwords.
- Home nodes can carry calls over their existing mesh while another participant's VPS is offline. Each person in a private-only call pairs their own reachable Spry node; a public Spry can also serve guests without a node.
- Settings can remove a pairing. Physical GrapheneOS calls over independent home networks still need acceptance.

## 0.6.110

- Calls restore their headset or speaker output when Android changes the communication route or activates call audio. This addresses a fallback to the quiet earpiece observed during a live call.
- Calls retry a sustained failed network connection with an ICE restart, then a bounded connection rebuild if needed, including after network or VPN changes.
- Loudspeaker echo, sustained two-way video and real network-change recovery still need physical GrapheneOS acceptance. This release does not claim to resolve every audio/video failure.

## 0.6.109

- Make a link from this phone. When this phone can no longer let people in with a room's link, because its permission from the device that made the link has run out (twelve hours after that device last let someone in) or the link was made on another device, the invite sheet says so and offers a link this phone answers. The new link names the room's owner key, so whoever joins with it catches up even in a room whose key has changed. Before this, a room shut to new devices once the device that made its link was gone. Older versions of KithMoot ask to be updated before they open such a link.
- The call relay (TURN) password has an eye button to show and hide it.

## 0.6.108

- Room info keeps a member's name on one line when it fits. "TheCryptoDonkey (you)" used to break mid-word because the name only ever got half the row.
- Use another device here too now says "Waiting for your signer…" while your signer app is being asked, and a second press does nothing meanwhile. With a hardware signer out of reach it used to sit silent for 15 seconds.
- The invite sheet says who lets people in with the room's link. A link that admits by itself says anyone can walk in. Otherwise it says arrivals ask and this phone lets them in, or, when this phone can no longer answer for the link, that the device that made it has to answer, and why. It used to say "can walk in" for every link.

## 0.6.107

- Audio and video are ready about a minute sooner when you open a room. Every open waited while the phone re-saved a record of everything you had written in the room, one message at a time, so "Joining when audio and video are ready…" lasted longer the more you had written. It now saves them together, and only what is new.
- A call with no call relay now says so when a link to someone is still trying after 20 seconds, as well as when it fails: "No call relay (TURN) is set up on this device, so this call may not connect on some networks. Add one in Settings › Connections." It used to say "Your group has none", which read as the room's relays.

## 0.6.106

- Calls ask Cloudflare's public STUN server for this phone's public address, instead of ForgeSworn's. A STUN server sees your IP address when a call starts, never the call itself. Settings › Connections › STUN server: use your own instead, on each phone that needs it. With your own call relay and no STUN server set, calls ask your relay. Hide my IP address on calls still asks no STUN server.
- A group that keeps its own file storage now keeps your files there too. Images and long pastes you send in its room go to the group's storage and nowhere else, and files others put there open on this phone. Room info says Files in this room go to the group's own storage.
- In such a room, a picked image waits in the message box until the group's storage takes it, and Send waits for it. Recordings can't be shared there yet.

## 0.6.105

- Settings › Connections › Call relay (TURN): save your own relay address, username and password on this phone. The login stays encrypted here and is never added to a room invitation. Save on each device that needs it, then leave and reopen the room. Hide my IP address on calls keeps using only the configured relay.
- The install/update notice above Chats clears the status bar and camera cut-out, without adding a second gap above the Chats header.

## 0.6.104

- Files go to a public server run by someone else unless you choose otherwise. The first image or long paste you send on this phone says where it goes, who runs the server and what it can see, and goes only when you press Upload or Send.
- Settings › Privacy › Where files go: a public server, your own server, or nowhere. Choose another server from that first notice without leaving the room.
- With file uploads off, a picked image is held and the app says why, with a way to turn them back on.
- Room info › Privacy and connection says where files you send in that room go.
- Upload errors say what went wrong: a server's size limit, a file type it won't store, or a connection that failed.

## 0.6.103

- Reply to a message: tap it and choose Reply. Your reply shows a quote of what it answers, and replies now sit in time order with everything else instead of under the message. Tap a quote to go to the message it answers; a deleted one says Message deleted, and one not loaded yet says Original message not loaded.
- ForgeSworn no longer runs a call relay. Calls connect directly, which works on most networks. If someone on a call cannot be reached that way, the call says: Some calls need a relay. Your group has none, so this call may not connect on some networks.
- With Hide my IP address on calls on, a call needs a relay, so in a room without one it doesn't start and says why. Turn the setting off in Settings › Privacy to join the call.
- KithMoot no longer sends files to ForgeSworn's storage. If you had chosen it, the image dialog says so once and asks where your files should go. Typing a ForgeSworn server is refused.
- A file its server no longer has says "This file is no longer on" and the server's name. ForgeSworn's storage stops keeping files on 23 October 2026, so files sent there before then stop opening.

## 0.6.102

- Choose what each room tells you about: in a room, ⋮ › Notifications, or press and hold a chat in Chats. Every message, Only mentions and replies, Nothing, or Use my default. Only mentions and replies tells you about a reply to you, a mention of you or of everyone, and everything in a private chat.
- Mute a room for 1 hour, 8 hours, 1 day or 1 week, from the same place, and Unmute to end it early. A muted room says until when under its name, shows a crossed-out bell and a grey count in Chats, and is left out of the Chats count. Its calls don't ring. A room set to Nothing counts as muted.
- Settings › Notifications: choose what rooms on Use my default tell you about.
- KithMoot can now read chat messages written in a newer format that keeps relays from seeing which device sent each one. It doesn't send them yet: every app needs to read the format before any app sends it. When a room self-destructs, your own messages in that format are deleted too.

## 0.6.101

- Settings › Privacy › Hide my IP address on calls. Calls go through a relay server, so other people on the call don't see your IP address. It can be slower. It is off unless you turn it on, and applies from the next room you open. If no relay server answers, calls in that room don't start; they never connect directly instead.
- A room nobody has named says Untitled room everywhere: its bar, Room info, Leave, the call bar, a ring and the call notification. The rename box starts empty for it.
- Somebody with no name shows as Somebody, never as a piece of their key: in Room info, on their messages, on a shared screen and in a private chat with them. Their picture shows S.
- In a room left open for over an hour, calls can still use the relay server: its sign-in is renewed every 40 minutes.
- relay.nostr.net, a default relay since 0.6.100, stopped answering, so relay.ditto.pub takes its place beside relay.primal.net and nostr.oxtr.dev. If you never changed your relays, this applies when the app next starts; a list you set yourself is kept.
- Signing in with Signet uses Signet's new relay, relay.primal.net, with nostr.oxtr.dev as a spare.

## 0.6.100

- Invite somebody to one of your rooms: Invite in People, or Invite to a room on a person's details in a room. Pick the room and press Send invite. The room's link goes to them sealed, so only they can open it; from People it arrives in your private chat with them.
- An invitation in a chat says who sent it, "Priya invited you to a room", with Accept. Your own says which room you invited them to. Somebody with no name shows as Somebody, never as a piece of their key, here and in Chats.
- Asking to join a temporary room uses the web's words: Ask to join, What should we call you?, Preview camera, Test microphone.
- New default relays: relay.primal.net, nostr.oxtr.dev and relay.nostr.net, in place of nos.lol and nostr.mom, which stopped answering around 19 October. If you never changed your relays, the new ones apply when the app next starts; a list you set yourself is kept.

## 0.6.99

- Decline and block. An invitation's ⋮ menu has Decline and block, and a person in People has Block. A block stays on this phone: it is never published, not even to your public Nostr mute list, and the person is not told. Their invitations and requests to join your rooms are dropped unanswered, and you cannot start a private chat with them; one already open stays until you leave it. Settings › Privacy lists who you have blocked and unblocks them.
- The image dialog no longer fills in ForgeSworn's file server. Pick the public server it lists or type your own. If you had agreed to the old suggestion, a one-off notice asks you to choose again before you next send a file.

## 0.6.98

- A new call rings for up to a minute from when it started, up from 45 seconds, then stops: banner, sound and notification together, as on the web. A call already going for a minute does not ring, including one whose ring reaches this phone more than a minute late; open the room and its Call on banner offers Join.
- Two people on a call with the same name show their short key after it, so they can be told apart. Nobody else's key shows.
- A ring from somebody this phone cannot name says Somebody, not "Someone in" and the room's name.
- The Effects headings say Hide your face and Hide what is behind you, as on the web.

## 0.6.97

- A room's bar, in step with the web and desktop apps: the room's name with who is here under it, then Call, Search in room and a ⋮ menu (Room info, Invite, New task, Pin, Leave). Chat and Board are tabs.
- A call going on in an open room shows a banner naming who is on it, with Join. While you are on a call, a bar shows how long it has run, with Mute, Return and Leave.
- Room info is a full page: quick actions, members, the room's settings, Leave and Report abuse.
- Settings names match the web: Notifications, Profile, Relays for private chats.
- The call controls are Mute, Camera, Chat, More and Leave. More holds Effects, Layout, Mirror my view, Record call and Invite people. Effects gathers Flip camera, Face and Background.
- An incoming call says who is calling and from which room, with large Decline and Answer buttons.
- People on a call are named, never shown by a key: somebody without a name shows as "Somebody", and you as "your name (you)".
- The first time you unmute on a call with an agent, a note says agents cannot hear you unless you say so, with Let agents hear me.
- An invitation says how long ago it was sent, as on the web.

## 0.6.96

- Chats, People and Boards along the bottom of the screen, or down the side on a tablet, in step with the web and desktop apps' new layout. Chats has filter chips, a New button, and received invitations at the top with Accept and Decline.
- People: your Signet contacts and contact cards, then the people your Nostr account follows, each A to Z. Message, Voice or Video from a person. Names for the people you follow are looked up only while public profiles are on.
- An invitation from somebody you follow shows their name, not a key.
- On a tablet the open room sits beside the list.
- Linking Signet contacts offers two boxes, both off by default, to also ask for Kin, Kith and Ken tiers and for check status.

## 0.6.95

- Sending is now visible: Send shows an hourglass and the text holds still while a message goes, and a slow send says there is no need to press Send again.
- A sent message no longer stays in the box when the keyboard changed it (an autocorrection, say) as Send was pressed, which invited a second Send.
- Keeper recovery holds on when its receiver is busy, and refused callers are completed instead of left waiting.

## 0.6.94

- Paste text of any length. A paste over 2,000 characters was silently discarded; it now goes as an encrypted text document with a short preview, as the web and desktop clients send it, through the storage server already chosen for images.
- Open text documents sent from any client: they show scrollable and selectable, with a Copy button.

## 0.6.93

- A calmer chat screen, in step with the web and desktop apps: one palette and type scale on the logo colours, messages grouped into runs by sender, reactions under the bubble instead of over it, an outlined "Agent" label beside agents' names, and a one-line composer (Add a file, the field with Emoji inside it, Send) whose controls share one centre line at every text size.
- Stronger outlines and muted text when Android's high contrast text setting is on.
- The room's back arrow is labelled "Back to chats". It always kept the room saved; Leave room is in Room details.
- Open an invitation to a room sent from the web client. It no longer fails as a private conversation.

## 0.6.92

- Use WebRTC's own echo canceller and noise suppressor instead of the phone's. On many phones the built-in canceller let a loudspeaker call echo back to listeners as heavy reverb.
- Roll back 0.6.90's quieter automatic replies while calls that lost sound and screen shares are investigated. Repeated and stale call signals, epoch recovery and keeper admission are answered at once again.

## 0.6.91

- Add an original full-picture masked avatar, remembered before capture starts, and prepare multi-face mask compositing with stale-worker publication fencing. Automatic face masking remains unavailable pending our own qualified model.
- Remove MediaPipe camera effects and Google background weights. A chosen backdrop replaces the whole picture until the owned foreground model is ready.

## 0.6.90

- Automatic replies no longer give away when the phone is in use. A repeated call signal is answered after a short random wait, at most once every two seconds per device, and stale ones the same way.
- A room's key desk answers after a short random wait, and refuses a removed member once an hour rather than every time. In a room closing with more than 16 people, some late arrivals see the room stop answering rather than a "closed" notice.

## 0.6.89

- Keep a relay's read and write choices across a restart when no account is signed in. Read-only and write-only relays no longer come back as both, and an unticked relay no longer disappears.

## 0.6.88

- Open a tapped KithMoot join link straight in the app rather than the browser, on kithmoot.forgesworn.dev and kithmoot.app.
- Hold a call tile's picture through a missed heartbeat instead of flashing black.
- A red record button and one compact recording row.

## 0.6.87

- Move a relay up or down in Settings. Order decides which relays a room link carries when it can name only some.
- Say when a relay accepts a stored message and then does not return it; Settings counts that relay as needing attention.
- Keep a refused relay read on show while another read on the same relay is answered.
- Describe a refused write by its reason, such as rate limiting or authentication, rather than the relay's own words.
- Keep reading older keeper requests from a relay that serves short pages.

## 0.6.86

- Find a Signet contact and start a private conversation without sharing a room first.
- Discover encrypted private invitations when returning later, including while the sender is offline.
- Reopen the same conversation for chat, calls and collaboration; explicitly open or dismiss invitations.
- Retain failed invitations for retry and check current account and blocks before delivery.
- Keep existing joined-room background calls and messages; first-contact invitations are read while the account is open.

# Changelog

Release notes for the KithMoot Android app. Zapstore reads the section for the
version it publishes (`release_notes` in `zapstore.yaml`).

## [0.6.76] - 2026-10-09

- Call recording warnings name the recorder and say whether they selected
  audio, gallery video, speaker video or a screen share with camera. People
  joining later see the same signed notice. Older or incomplete notices retain
  the broader audio/video warning.
- Recording details belong to the room, recorder and exact recording notice;
  unrelated or altered details cannot change the warning. Your active call
  device advertises support so a web recorder can include its camera safely.

Recording and exporting a clip from the Android app are not available yet.
Recordings saved or shared by another person survive a temporary room ending.

## [0.6.65] - 2026-10-08

For people using the optional VMLS rooms preview on their own Bothy box:

- Report another of your own devices as lost or compromised. This phone
  starts its removal where the room can change and asks the keeper to
  revoke its box access immediately.
- Keepers can review, defer or decline requests. An approved request revokes
  that device's grants at the keeper's boxes without the usual removal grace,
  including after the requesting phone has left the room.
- Sent means a relay accepted the request, not that the keeper has acted.
  Publishing the request does not authenticate as the member to the relay.

The VMLS preview remains off by default. Requests need a verified keeper
identity and the keeper's DM relay list. Delivery and keeper action depend
on the relay and the keeper opening the app with their signer.

## [0.6.64] - 2026-10-08

For people trying the VMLS rooms preview on their own Bothy box (off until
turned on in Settings, Connections); nothing else changes.

- When the box has had its hourly share of changes to members or keys from
  this phone, the room says "waiting on the box" and that the change goes out
  by itself within the hour, rather than showing "Sending" with no reason.
- A removal the room's engine refuses for good is shown as stopped, with
  "Try the Remove again"; refusals that clear by themselves wait instead.
- Removing a device as compromised, or closing a room, without your signer
  now says to sign in again, rather than doing nothing.

## [0.6.63] - 2026-10-08

- Rooms can self-destruct. A new room with an end date offers
  "Self-destruct" or "Keep it read-only", and shows a green, amber and red
  countdown. When a self-destructing room ends, this phone leaves it, asks
  relays to delete its own messages there, and wipes what it kept of the
  room, even while the app is closed if background ringing or delivery is
  on (otherwise at the next start). A room joined from a web link now takes
  its name from the link.
- Settings is now a list of pages: your account, notifications and calls,
  display, privacy, connections and updates, side by side on a tablet or
  a wide screen.
- For people who run their own Bothy box: VMLS rooms and the restore
  witness, as a preview, off until turned on in Settings, Connections.
  They need a box that offers VMLS, which no Bothy release does yet; while
  the preview is off, none of it shows and nothing contacts a box.
- Settings show what is really happening: with notifications blocked, the
  notification and background options read off and say why, and signing
  out asks first.
- Security: the connection library is updated (rustls 0.23.45).

## [0.6.62] - 2026-10-06

- On a bad connection you can keep writing. Messages that cannot send yet
  wait at the end of the chat as your own, saying whether they are pending,
  sending, turned down or not yet confirmed, and go by themselves, oldest
  first, once a relay answers. Before, a second message was refused until
  the first had gone.
- A message that has not left your phone can be edited or deleted, and
  nobody ever sees the first version. One that may already have arrived can
  only be taken off the list, and says so.
- Rooms saved to your account reach your other devices even when a relay
  has lost them: the phone puts back what the relays dropped.

## [0.6.61] - 2026-10-06

- The app is about a quarter of the size to download: 38 MB instead of 142 MB.
  It no longer carries a second copy of its native code for Intel processors,
  which no phone that can install it uses, and its code is compressed inside
  the download.

## [0.6.60] - 2026-10-06

- When a room's key changes on its regular schedule, nothing is announced in
  the chat. A key change that removes someone is still announced.
- Notifications and call rings keep arriving for rooms you have not opened
  after a room's key changes: the phone follows the change in the
  background, using the copy sealed to it.
- A phone that joins a room, or comes back to one after a while, reads the
  room's last month, up to 16 key changes back, where it used to read 4.

## [0.6.59] - 2026-10-06

- A call no longer starts by itself. After a call ended, a phone could start
  a new one on its own - just after Leave, or while its relays were catching
  up - and everyone in the room was rung "… is calling" with nobody calling.
  Now only pressing Join starts a call; otherwise the phone rejoins the call
  it was on.
- A new call in a room you were on a call in less than a minute ago does not
  ring. It still shows in the room. This keeps phones quiet if someone on an
  older version starts a call by accident.

## [0.6.58] - 2026-10-05

- The rooms list is easier to scan when you have a lot of rooms. Rooms are
  grouped into Pinned, Recent and Older, with ended rooms at the end;
  Older and Ended fold away behind a count until you open them. With eight
  rooms or fewer it stays one list.
- Pin a room from its ⋯ menu to keep it at the top. Pins stay on this phone,
  and forgetting a room removes its pin.
- Each room has a coloured initial, and rows are shorter with no divider lines.
- Search, Projects and Open invite link are at the top of the list. Opening
  an invite link and signing in are in the ⋮ menu, rather than under your
  last room.

## [0.6.57] - 2026-10-05

- Forgetting a room now erases its keys from this phone: the current room key,
  the past keys it kept to bring other people's devices up to date, and the
  list of who the room knows. Reset saved rooms erases them for every room.
  Keys left behind by rooms you forgot on earlier versions are erased the
  first time this version opens.
- Stopping a Bothy's quiet cadence for a room no longer fails for up to an
  hour when the phone's clock is slightly behind the Bothy's.

## [0.6.56] - 2026-10-04

- Uses less battery in the background. Rooms that use the same relays now
  share one connection while the app is closed, so the phone keeps far fewer
  connections open and wakes its radio less often.
- A room left open behind other apps for 5 minutes is handed to the background
  service, the same as closing it. Others see you leave the room, but calls
  still ring and messages still arrive as notifications. Coming back to
  KithMoot reopens the room. A call, screen share, recording or unsent message
  keeps the room open.

## [0.6.55] - 2026-10-04

- Run a call as a meeting from your phone. In a room you made, Run as a meeting
  in the call's More menu turns meeting mode on and off and lists everybody,
  raised hands first, so you can make people speakers or stop them. Only
  speakers can talk or show video, on every phone, computer and browser.

## [0.6.54] - 2026-10-04

- KithMoot can now update itself if you installed it from the downloads page.
  It checks for a new version every few hours while open, and installs one only
  if it matches a release manifest signed with a key built into the app, at the
  exact size and fingerprint that manifest gives, and signed by the same key as
  the app you already have. Updates wait until you are off a call. If you
  installed KithMoot from Zapstore, it tells you an update is ready and opens
  Zapstore to install it. Settings has a Check for updates button and a switch
  for automatic checks.

## [0.6.53] - 2026-10-04

- Meeting mode, as on the web and desktop. When the person who made a room runs
  its call as a meeting, only the speakers they choose can talk or show video.
  Everybody else's microphone, camera and screen share stay off, with Raise
  your hand to ask to speak, and your phone does not play or show anybody who
  is not a speaker, whatever their app sends.
- When a call is being recorded, a red notice says so in the room, and
  KithMoot asks before you join the call. You can stay in the room and read
  the chat without joining.
- Links in chat messages can be tapped.

## [0.6.52] - 2026-10-03

- Once a room has removed somebody, its key goes only to people it knows, so a
  removed person stays out.

## [0.6.51] - 2026-10-03

- When you start a call on another of your devices, your phone shows a quiet
  notice with Join from this phone, instead of staying silent.
