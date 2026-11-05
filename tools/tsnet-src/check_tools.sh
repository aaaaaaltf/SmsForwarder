#!/bin/bash
export PATH="/c/Program Files/Git/usr/bin:/c/Program Files/Git/bin:/usr/bin:/bin:$PATH"
echo "zip: $(which zip || echo MISSING)"
echo "unzip: $(which unzip || echo MISSING)"
echo "make: $(which make || echo MISSING)"
echo "curl: $(which curl || echo MISSING)"
