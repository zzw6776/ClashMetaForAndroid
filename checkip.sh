#!/usr/bin/env bash
set -euo pipefail

WORKDIR="ruleset-checker"
RULEDIR="${WORKDIR}/rules"
NORMDIR="${WORKDIR}/normalized"

mkdir -p "$RULEDIR" "$NORMDIR"

# =========================
# 你要检查的域名/IP，写这里
# =========================
TARGETS=(
"clash-static.inbox.supercell.com"
)

# =========================
# ruleset 下载地址
# =========================
download_all() {
  echo "Downloading rulesets in parallel ..."

  curl -L --fail --retry 3 --connect-timeout 20 \
    -o "$RULEDIR/category-ads-all.list" \
    "https://raw.githubusercontent.com/MetaCubeX/meta-rules-dat/meta/geo/geosite/category-ads-all.list" &

  curl -L --fail --retry 3 --connect-timeout 20 \
    -o "$RULEDIR/private.list" \
    "https://raw.githubusercontent.com/MetaCubeX/meta-rules-dat/meta/geo/geosite/private.list" &

  curl -L --fail --retry 3 --connect-timeout 20 \
    -o "$RULEDIR/private-ip.list" \
    "https://raw.githubusercontent.com/MetaCubeX/meta-rules-dat/meta/geo/geoip/private.list" &

  curl -L --fail --retry 3 --connect-timeout 20 \
    -o "$RULEDIR/geolocation-cn.list" \
    "https://raw.githubusercontent.com/MetaCubeX/meta-rules-dat/meta/geo/geosite/geolocation-cn.list" &

  curl -L --fail --retry 3 --connect-timeout 20 \
    -o "$RULEDIR/cn-ip.list" \
    "https://raw.githubusercontent.com/MetaCubeX/meta-rules-dat/meta/geo/geoip/cn.list" &

  curl -L --fail --retry 3 --connect-timeout 20 \
    -o "$RULEDIR/geolocation-not-cn.list" \
    "https://raw.githubusercontent.com/MetaCubeX/meta-rules-dat/meta/geo/geosite/geolocation-!cn.list" &

  curl -L --fail --retry 3 --connect-timeout 20 \
    -o "$RULEDIR/cn.list" \
    "https://raw.githubusercontent.com/MetaCubeX/meta-rules-dat/meta/geo/geosite/cn.list" &

  wait
  echo "Download finished."
}

normalize_one_domain_ruleset() {
  local name="$1"
  local src="$RULEDIR/${name}.list"

  echo "Normalizing $name ..."

  # suffix 规则
  sed -E '
    s/\r$//;
    s/[[:space:]]+#.*$//;
    s/^[[:space:]]+//;
    s/[[:space:]]+$//;
    /^$/d;
    /^#/d;
    s/^domain://;
    s/^\+\.//;
    s/^\.//;
  ' "$src" \
    | grep -vE '^(full:|keyword:|regexp:)' \
    | tr '[:upper:]' '[:lower:]' \
    | sort -u > "$NORMDIR/${name}.suffix"

  # full 规则
  sed -E '
    s/\r$//;
    s/[[:space:]]+#.*$//;
    s/^[[:space:]]+//;
    s/[[:space:]]+$//;
    /^$/d;
    /^#/d;
  ' "$src" \
    | grep -E '^full:' \
    | sed -E 's/^full://' \
    | tr '[:upper:]' '[:lower:]' \
    | sort -u > "$NORMDIR/${name}.full"

  # keyword 规则
  sed -E '
    s/\r$//;
    s/[[:space:]]+#.*$//;
    s/^[[:space:]]+//;
    s/[[:space:]]+$//;
    /^$/d;
    /^#/d;
  ' "$src" \
    | grep -E '^keyword:' \
    | sed -E 's/^keyword://' \
    | tr '[:upper:]' '[:lower:]' \
    | sort -u > "$NORMDIR/${name}.keyword"

  # regexp 规则
  sed -E '
    s/\r$//;
    s/[[:space:]]+#.*$//;
    s/^[[:space:]]+//;
    s/[[:space:]]+$//;
    /^$/d;
    /^#/d;
  ' "$src" \
    | grep -E '^regexp:' \
    | sed -E 's/^regexp://' \
    > "$NORMDIR/${name}.regexp"

  echo "Normalized $name."
}

normalize_all() {
  echo "Normalizing domain rulesets in parallel ..."

  normalize_one_domain_ruleset "category-ads-all" &
  normalize_one_domain_ruleset "private" &
  normalize_one_domain_ruleset "geolocation-cn" &
  normalize_one_domain_ruleset "geolocation-not-cn" &
  normalize_one_domain_ruleset "cn" &

  wait
  echo "Normalize finished."
}

is_ip() {
  python3 - "$1" <<'PY'
import ipaddress, sys
try:
    ipaddress.ip_address(sys.argv[1])
    sys.exit(0)
except Exception:
    sys.exit(1)
PY
}

normalize_target() {
  local v="$1"

  v="$(echo "$v" | sed -E 's#^https?://##I')"
  v="${v%%/*}"
  v="${v#[}"
  v="${v%]}"

  # 普通 domain:port 去掉端口，IPv6 不处理
  if [[ "$v" != *":"*":"* && "$v" == *":"* ]]; then
    v="${v%%:*}"
  fi

  echo "$v" | tr '[:upper:]' '[:lower:]' | sed -E 's/^\.+//; s/\.+$//'
}

domain_parent_candidates() {
  local domain="$1"
  local cur="$domain"

  while true; do
    echo "$cur"
    local next="${cur#*.}"
    if [[ "$next" == "$cur" ]]; then
      break
    fi
    cur="$next"
  done
}

domain_match_ruleset() {
  local domain="$1"
  local name="$2"

  local suffix_file="$NORMDIR/${name}.suffix"
  local full_file="$NORMDIR/${name}.full"
  local keyword_file="$NORMDIR/${name}.keyword"
  local regexp_file="$NORMDIR/${name}.regexp"

  # full 精确匹配
  if [[ -s "$full_file" ]] && grep -Fxq "$domain" "$full_file"; then
    echo -e "${name}\tfull\t${domain}"
    return 0
  fi

  # suffix 父域匹配
  local hit
  hit="$(domain_parent_candidates "$domain" | grep -Fxf "$suffix_file" | head -1 || true)"
  if [[ -n "$hit" ]]; then
    echo -e "${name}\tsuffix\t${hit}"
    return 0
  fi

  # keyword 匹配
  if [[ -s "$keyword_file" ]]; then
    while IFS= read -r kw; do
      [[ -z "$kw" ]] && continue
      if [[ "$domain" == *"$kw"* ]]; then
        echo -e "${name}\tkeyword\t${kw}"
        return 0
      fi
    done < "$keyword_file"
  fi

  # regexp 匹配
  if [[ -s "$regexp_file" ]]; then
    while IFS= read -r reg; do
      [[ -z "$reg" ]] && continue
      if echo "$domain" | grep -Eq "$reg" 2>/dev/null; then
        echo -e "${name}\tregexp\t${reg}"
        return 0
      fi
    done < "$regexp_file"
  fi

  return 1
}

ip_match_ruleset() {
  local ip="$1"
  local name="$2"
  local file="$RULEDIR/${name}.list"

  python3 - "$ip" "$name" "$file" <<'PY'
import ipaddress
import sys

ip_text = sys.argv[1]
name = sys.argv[2]
file_path = sys.argv[3]

ip = ipaddress.ip_address(ip_text)

with open(file_path, "r", encoding="utf-8", errors="ignore") as f:
    for raw in f:
        line = raw.strip()
        if not line or line.startswith("#"):
            continue

        if " #" in line:
            line = line.split(" #", 1)[0].strip()

        lower = line.lower()
        if lower.startswith("ip-cidr,") or lower.startswith("ip-cidr6,"):
            parts = line.split(",")
            if len(parts) >= 2:
                line = parts[1].strip()

        try:
            net = ipaddress.ip_network(line, strict=False)
        except Exception:
            continue

        if ip in net:
            print(f"{name}\tcidr\t{line}")
            sys.exit(0)
PY
}

check_target() {
  local raw="$1"
  local target
  target="$(normalize_target "$raw")"

  [[ -z "$target" ]] && return

  local hit=0

  if is_ip "$target"; then
    for rs in private-ip cn-ip; do
      local result
      result="$(ip_match_ruleset "$target" "$rs" || true)"
      if [[ -n "$result" ]]; then
        printf '%s\tIP\t%s\n' "$target" "$result"
        hit=1
      fi
    done

    if [[ "$hit" == "0" ]]; then
      printf '%s\tIP\t-\t-\t-\n' "$target"
    fi
  else
    for rs in category-ads-all private geolocation-cn geolocation-not-cn cn; do
      local result
      result="$(domain_match_ruleset "$target" "$rs" || true)"
      if [[ -n "$result" ]]; then
        printf '%s\tDOMAIN\t%s\n' "$target" "$result"
        hit=1
      fi
    done

    if [[ "$hit" == "0" ]]; then
      printf '%s\tDOMAIN\t-\t-\t-\n' "$target"
    fi
  fi
}

main() {
  download_all
  normalize_all

  echo
  (
    printf 'TARGET\tTYPE\tRULESET\tMATCH_TYPE\tMATCHED_RULE\n'
    for t in "${TARGETS[@]}"; do
      check_target "$t"
    done | sort -t $'\t' -k3,3
  ) | column -t -s $'\t'

  echo
  echo "Cache dir: $RULEDIR"
  echo "Normalized dir: $NORMDIR"
}

main