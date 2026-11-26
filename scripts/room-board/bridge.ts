// The room board page's own script. The sole bridge to the app is one transferred MessagePort; the app
// holds every key and relay, and this page holds the board (KithMoot's RoomBoardSync) and Girnel's room
// view over it, in the same-origin frame `#view`. No network API is used.
import { openRoomBoardOverPort } from '../../../kithmoot/src/room-board-port.js'

type GirnelWindow = Window & { girnelRoomBoard?: { start(host: unknown): void; hasPendingEdits(): boolean } }

let started = false
window.onmessage = event => {
  if (started || event.ports.length !== 1) return
  started = true
  const frame = document.getElementById('view') as HTMLIFrameElement
  let view: GirnelWindow['girnelRoomBoard']
  void openRoomBoardOverPort(event.ports[0]!, { pendingEdits: () => view?.hasPendingEdits() ?? false }).then(board => {
    // The view lasts as long as the page: the app keeps the page while the room is open, shown or not.
    const signal = new AbortController().signal
    const mount = () => {
      const child = frame.contentWindow as GirnelWindow | null
      if (!child || view) return
      const start = () => {
        if (view || !child.girnelRoomBoard) return
        child.girnelRoomBoard.start(board.view(signal))
        view = child.girnelRoomBoard
      }
      if (child.girnelRoomBoard) start(); else child.addEventListener('girnel-room-ready', start, { once: true })
    }
    if (frame.contentDocument?.readyState === 'complete') mount()
    frame.addEventListener('load', mount)
  })
}
