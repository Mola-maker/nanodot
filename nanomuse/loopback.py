"""Connecting to ourselves on Windows.

Windows has no ``socketpair()``, so Python emulates it: a listening socket on 127.0.0.1, a
connect to it, an ``accept``. asyncio needs one such pair for every event loop (its
"self-pipe"), before the loop runs a single step. On a machine where something intercepts
local connections — a proxy client with every connection routed through it (Proxifier, a
TUN mode), security software holding an unknown program's ports — that ``accept`` never
returns, the stdlib has no timeout on it, and ``nanomuse serve`` sits there forever,
listening on nothing. That was a Windows desktop that "would not open" without a word.

:func:`install` replaces ``socket.socketpair`` on Windows with one that waits a few seconds,
tries the IPv6 loopback when the IPv4 one is intercepted (a TUN mode often takes only IPv4),
and otherwise fails with an explanation. :func:`check` is the same test done once up front
so the server can say what is wrong and stop instead of hanging.
"""

from __future__ import annotations

import os
import socket
from typing import Any

from nanomuse.logger import logger

#: how long one loopback connection may take before it counts as intercepted
TIMEOUT_S = 3.0

MARKER = "loopback blocked:"

EXPLANATION = (
    "Nanodot cannot connect to itself: a connection from this computer to 127.0.0.1 never "
    "completes (nor one to ::1). Something on this machine intercepts local connections: "
    "usually a proxy client that routes every connection (Proxifier; Clash, V2Ray or Surge in "
    "TUN mode; a game accelerator) or security software. Make 127.0.0.1 and localhost connect "
    "directly (Proxifier: Profile → Proxification Rules → Localhost → Direct; Clash: turn TUN "
    "mode off or exclude 127.0.0.1), or add Nanodot to its exceptions, then start Nanodot "
    "again."
)

_families: tuple[int, ...] = (socket.AF_INET, socket.AF_INET6)
_LOOPBACK: dict[int, str] = {socket.AF_INET: "127.0.0.1", socket.AF_INET6: "::1"}
# the loopback that worked last, tried first from then on (an intercepted 127.0.0.1 costs
# TIMEOUT_S once, not on every loop)
_preferred: int | None = None
_original: Any = None


class LoopbackBlocked(OSError):
    """No loopback connection completes on this machine."""

    def __init__(self, detail: str = "") -> None:
        super().__init__(f"{MARKER} {EXPLANATION}" + (f" ({detail})" if detail else ""))


def _pair(family: int, timeout: float) -> tuple[socket.socket, socket.socket]:
    """The stdlib's emulation, with a timeout on the accept."""
    host = _LOOPBACK[family]
    lsock = socket.socket(family, socket.SOCK_STREAM, 0)
    try:
        lsock.bind((host, 0))
        lsock.listen()
        addr, port = lsock.getsockname()[:2]
        csock = socket.socket(family, socket.SOCK_STREAM, 0)
        try:
            csock.setblocking(False)
            try:
                csock.connect((addr, port))
            except (BlockingIOError, InterruptedError):
                pass
            csock.setblocking(True)
            lsock.settimeout(timeout)
            ssock, _ = lsock.accept()
        except BaseException:
            csock.close()
            raise
    finally:
        lsock.close()
    try:
        if ssock.getsockname() != csock.getpeername() or csock.getsockname() != ssock.getpeername():
            raise ConnectionError("unexpected peer connection")
    except BaseException:
        ssock.close()
        csock.close()
        raise
    return ssock, csock


def socketpair(
    family: int = socket.AF_INET, type: int = socket.SOCK_STREAM, proto: int = 0
) -> tuple[socket.socket, socket.socket]:
    """``socket.socketpair`` for Windows: a timeout, the other loopback as a fallback, and
    an explanation instead of a hang."""
    global _preferred
    if type != socket.SOCK_STREAM or proto != 0 or family not in _families:
        if _original is not None:
            return _original(family, type, proto)
        raise ValueError("only SOCK_STREAM loopback pairs are supported")
    order = [family] + [f for f in _families if f != family]
    if _preferred is not None and _preferred in order:
        order.remove(_preferred)
        order.insert(0, _preferred)
    errors: list[str] = []
    for fam in order:
        try:
            pair = _pair(fam, TIMEOUT_S)
        except (OSError, ValueError) as exc:
            errors.append(f"{_LOOPBACK[fam]}: {exc}")
            continue
        if _preferred != fam:
            if fam != family or errors:
                logger.warning(
                    "loopback: {} is intercepted on this machine, using {}",
                    _LOOPBACK[family],
                    _LOOPBACK[fam],
                )
            _preferred = fam
        return pair
    raise LoopbackBlocked("; ".join(errors))


def install() -> None:
    """On Windows, make ``socket.socketpair`` the guarded one (idempotent)."""
    global _original
    if os.name != "nt" or _original is not None:
        return
    _original = getattr(socket, "socketpair", None)
    socket.socketpair = socketpair  # type: ignore[assignment]


def check() -> str:
    """One loopback pair, closed again. Empty when it works, the explanation otherwise."""
    if os.name != "nt":
        return ""
    try:
        a, b = socketpair()
    except OSError as exc:
        return str(exc)
    a.close()
    b.close()
    return ""


def preferred() -> str:
    """The loopback address the pairs use ("" before the first one)."""
    return _LOOPBACK[_preferred] if _preferred is not None else ""


__all__ = [
    "EXPLANATION",
    "MARKER",
    "TIMEOUT_S",
    "LoopbackBlocked",
    "check",
    "install",
    "preferred",
    "socketpair",
]
