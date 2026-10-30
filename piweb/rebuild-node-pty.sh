#!/data/data/com.termux/files/usr/bin/sh
# Rebuild the node-pty native module for Termux/Android (arm64).
# Why: Node reports process.platform "android", while node-pty ships prebuilds
# only for linux/darwin/win32, and node's common.gypi requires the
# android_ndk_path define whenever OS=="android".
set -e
PTY_DIR="$HOME/piweb/node_modules/node-pty"
NODE_GYP="$PREFIX/lib/node_modules/npm/node_modules/node-gyp/bin/node-gyp.js"
cd "$PTY_DIR"
GYP_DEFINES="android_ndk_path=$PREFIX" node "$NODE_GYP" rebuild
ls -l "$PTY_DIR/build/Release/pty.node"
