#!/usr/bin/env bash
# Source after env.sh. Validate Go build metadata rather than trusting PATH.
ensure_nanodot_mobile_tools() {
  local mobile_version mobile_bin tool tool_version
  mobile_version="${NANODOT_GOMOBILE_VERSION:-v0.0.0-20260908204917-8b95e45f8d3e}"
  mobile_bin="${GOPATH:-$(go env GOPATH)}"
  mobile_bin="${mobile_bin%%:*}/bin"
  mkdir -p "$mobile_bin"
  for tool in gomobile gobind; do
    tool_version=""
    if [[ -x "$mobile_bin/$tool" ]]; then
      tool_version="$(go version -m "$mobile_bin/$tool" | awk '$1 == "mod" && $2 == "golang.org/x/mobile" { print $3; exit }')"
    fi
    if [[ "$tool_version" != "$mobile_version" ]]; then
      GOBIN="$mobile_bin" go install "golang.org/x/mobile/cmd/$tool@$mobile_version"
      tool_version="$(go version -m "$mobile_bin/$tool" | awk '$1 == "mod" && $2 == "golang.org/x/mobile" { print $3; exit }')"
      [[ "$tool_version" == "$mobile_version" ]] || {
        echo "error: $tool build metadata does not match the pinned mobile version" >&2
        return 1
      }
    fi
  done
  export PATH="$mobile_bin:$PATH"
  hash -r
}
