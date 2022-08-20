#/bin/sh

if [[ "$1" == "view" ]]
then
  cat "$2"
else
  exit 1
fi

