#!/usr/bin/env python3
"""Pretends to be the camera for the app's placeholder UDP protocol.

Run on a computer on the same network as the phone, then enter that computer's
IP in the app's profile:

    python udp_simulator.py --port 5000
"""
import argparse
import math
import socket
import time


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=5000, help="UDP port to listen on")
    args = parser.parse_args()

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("0.0.0.0", args.port))
    sock.settimeout(0.05)
    print(f"Listening on UDP {args.port}")

    peer = None
    tracking = False
    box = [0.45, 0.45, 0.1, 0.1]
    pan = tilt = zoom = 0.0
    last_send = 0.0

    while True:
        try:
            data, peer = sock.recvfrom(2048)
            fields = data.decode("ascii", "replace").strip().split(",")
            if fields[0] != "HB":
                print(",".join(fields))
            if fields[0] == "MOVE":
                pan += float(fields[1])
                tilt += float(fields[2])
            elif fields[0] == "ZOOM":
                zoom += float(fields[1])
            elif fields[0] == "TRACK_POINT":
                box = [float(fields[1]) - 0.05, float(fields[2]) - 0.05, 0.1, 0.1]
                tracking = True
            elif fields[0] == "TRACK_BOX":
                box = [float(value) for value in fields[1:5]]
                tracking = True
            elif fields[0] == "TRACK_STOP":
                tracking = False
        except socket.timeout:
            pass

        now = time.time()
        if peer is None or now - last_send < 0.1:
            continue
        last_send = now

        if tracking:
            x = box[0] + 0.02 * math.sin(now * 2)
            y = box[1] + 0.02 * math.cos(now * 2)
            sock.sendto(f"TRACK,LOCKED,{x:.3f},{y:.3f},{box[2]:.3f},{box[3]:.3f}".encode(), peer)
        else:
            sock.sendto(b"TRACK,IDLE,0,0,0,0", peer)
        sock.sendto(f"TEL,pan={pan:.1f},tilt={tilt:.1f},zoom={zoom:.1f}".encode(), peer)


if __name__ == "__main__":
    main()
