#!/bin/sh
set -eu
mkdir -p "$1"
ffmpeg -hide_banner -loglevel error -f lavfi -i color=c=blue:s=4096x2160:r=60 -frames:v 1 -c:v libx264 -profile:v main -preset ultrafast -f h264 -y "$1/frame.h264"
ffmpeg -hide_banner -loglevel error -f lavfi -i color=c=blue:s=4096x2160:r=60 -frames:v 1 -c:v libvpx -deadline realtime -cpu-used 8 -f ivf -y "$1/frame.vp8"
ffmpeg -hide_banner -loglevel error -f lavfi -i color=c=blue:s=4096x2160:r=60 -frames:v 1 -c:v libvpx-vp9 -deadline realtime -cpu-used 8 -f ivf -y "$1/frame.vp9"
