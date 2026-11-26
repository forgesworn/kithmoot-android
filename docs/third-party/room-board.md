# The room board page

A room's board is the same on Android as on the web: KithMoot's board
(`src/room-board-sync.ts`, kept in step over a message port by
`src/room-board-port.ts`) with Girnel's room view over it. Both are pinned to
KithMoot
[74c71c1c4d3cda98c22a6a0892191b08cd3bfb46](https://github.com/forgesworn/kithmoot/tree/74c71c1c4d3cda98c22a6a0892191b08cd3bfb46).
Girnel's view is the one KithMoot vendors there:
`vendor/forgesworn-girnel-embed-1.1.0-kithmoot.11.tgz`, built from Girnel commit
`983be7871743cac51adb61bf02844261777c32ca`, copied byte for byte. Both are MIT
licensed. The notices for every package the page's script bundles, and Girnel's
own, are in `app/src/main/assets/room-board/THIRD-PARTY-NOTICES.txt`.

Run `npm ci` in a clean checkout of that exact KithMoot revision, then:

```
node scripts/build-room-board.mjs /path/to/kithmoot
python3 scripts/check-room-board.py
```

CI rebuilds the page and refuses any byte difference. Every debug and release
build checks the script, the bridge, the notices and each of Girnel's files
against the committed provenance, and refuses a file it does not name.
Updating the pin requires reviewing the board and refreshing both the
provenance and the checks; no code is fetched at runtime.

The page holds no key, credential, relay address or account. `RoomBoardPage`
serves it on a fresh random HTTPS origin from its pinned files alone: the page
itself, its script, and Girnel's files at the paths Girnel's page names
(`/j/girnel/...`), each with a content security policy. The policy for
Girnel's files is KithMoot's own for them, with no network. Any other request
gets a local refusal. Network loads, navigation, file and content access,
geolocation and extra windows are off. DOM storage is on, because Girnel keeps
its theme, text size and half-typed text there; it belongs to the page's own
origin and is deleted with the page. The app hands the page one
[message port to that exact origin](https://developer.android.com/reference/android/webkit/WebView#postWebMessage(android.webkit.WebMessage,%20android.net.Uri))
and exposes no Java object. What crosses the port is KithMoot's
`docs/room-board-port.md`.

`RoomBoardPageTest` runs the pinned page in a real WebView on a disposable
emulator, with the test as the app. Girnel's view mounts over the board with
the three columns every room starts from, and says the board is shared. A card
typed into it reaches the app as a part to seal and is kept in the app's copy,
and the page asks for nothing outside its pinned files.
