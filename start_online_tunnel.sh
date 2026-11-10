#!/bin/zsh
# Starts a free public HTTPS tunnel to local port 5050 using built-in macOS SSH (no brew required)
echo "=========================================================="
echo "  Madhav Maintenance Payment - Public Online Link"
echo "=========================================================="
echo "Connecting to localhost.run (no installation required)..."
echo "Look for the https://....lhr.life link printed below!"
echo "=========================================================="
ssh -o StrictHostKeyChecking=accept-new -o ServerAliveInterval=30 -R 80:localhost:5050 nokey@localhost.run
