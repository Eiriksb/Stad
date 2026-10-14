#!/bin/sh
# Paints Stad's textures in Krita (see paint.py). Krita may crash on the way out after it's done: that's harmless.
cd "$(dirname "$0")/../.." && PYTHONPATH=tools/art kritarunner -s paint "$PWD" "$@" 2>&1 | grep -v "^$" | grep -v "added to PYTHONPATH"
exit 0
