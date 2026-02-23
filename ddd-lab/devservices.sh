#!/usr/bin/env bash
set -u

service_labels=()
service_urls=()
service_containers=()
service_admin_info=()

first_mapped_port() {
  local cid="$1"
  local internal_port="$2"
  local line
  line="$(docker port "$cid" "${internal_port}/tcp" 2>/dev/null | head -n1 || true)"
  if [[ -z "${line}" ]]; then
    return 1
  fi
  echo "${line##*:}"
}

add_service() {
  local label="$1"
  local url="$2"
  local container="$3"
  local admin_info="$4"
  service_labels+=("$label")
  service_urls+=("$url")
  service_containers+=("$container")
  service_admin_info+=("$admin_info")
}

discover_services() {
  local cid cname cimage port
  while read -r cid cname cimage; do
    [[ -z "${cid:-}" ]] && continue

    port="$(first_mapped_port "$cid" "9001" || true)"
    if [[ -n "${port}" ]]; then
      add_service "MinIO" "http://localhost:${port}" "$cname" "adminadmin / adminadmin"
    fi

    port="$(first_mapped_port "$cid" "15672" || true)"
    if [[ -n "${port}" ]]; then
      add_service "RabbitMQ" "http://localhost:${port}" "$cname" "guest / guest"
    fi
  done < <(docker ps --format '{{.ID}} {{.Names}} {{.Image}}')

  if docker ps --filter "publish=8081" --format '{{.ID}}' | grep -q .; then
    local keycloak_name
    keycloak_name="$(docker ps --filter "publish=8081" --format '{{.Names}}' | head -n1)"
    add_service "Keycloak" "http://localhost:8081" "$keycloak_name" "admin / admin"
  fi
}

open_in_browser() {
  local url="$1"
  if command -v xdg-open >/dev/null 2>&1; then
    xdg-open "$url" >/dev/null 2>&1 &
  elif command -v open >/dev/null 2>&1; then
    open "$url" >/dev/null 2>&1 &
  elif command -v wslview >/dev/null 2>&1; then
    wslview "$url" >/dev/null 2>&1 &
  else
    echo "No browser opener found (xdg-open/open/wslview)."
    echo "Open manually: $url"
    return 1
  fi
}

draw_menu() {
  local selected_row="$1"
  local selected_col="$2" # 0=admin, 1=logs, 2=container

  clear
  echo "Select service/action (Up/Down + Left/Right, Enter, q):"
  echo
  printf "   %-12s | %-28s | %-10s | %-16s\n" "Service" "Admin Console (login / pass)" "Logs" "Container"
  printf "   %s\n" "------------------------------------------------------------------------------------"

  local i
  for i in "${!service_labels[@]}"; do
    local service="${service_labels[$i]}"
    local admin="${service_admin_info[$i]}"
    local logs="show logs"
    local container="enter container"

    local admin_cell="  ${admin}"
    local logs_cell="  ${logs}"
    local container_cell="  ${container}"

    if [[ "$i" -eq "$selected_row" && "$selected_col" -eq 0 ]]; then
      admin_cell="> ${admin}"
    fi
    if [[ "$i" -eq "$selected_row" && "$selected_col" -eq 1 ]]; then
      logs_cell="> ${logs}"
    fi
    if [[ "$i" -eq "$selected_row" && "$selected_col" -eq 2 ]]; then
      container_cell="> ${container}"
    fi

    printf "   %-12s | %-28s | %-10s | %-16s\n" "$service" "$admin_cell" "$logs_cell" "$container_cell"
  done

  echo
  printf "Selected URL: %s\n" "${service_urls[$selected_row]}"
  printf "Selected logs: docker logs -f %s\n" "${service_containers[$selected_row]}"
  printf "Selected shell: docker exec -ti %s sh\n" "${service_containers[$selected_row]}"
}

interactive_select() {
  local row=0
  local col=0
  local key

  trap 'tput cnorm 2>/dev/null || true' EXIT
  tput civis 2>/dev/null || true

  while true; do
    draw_menu "$row" "$col"
    IFS= read -rsn1 key
    case "$key" in
      q|Q)
        echo
        exit 0
        ;;
      "")
        clear
        if [[ "$col" -eq 0 ]]; then
          echo "Opening ${service_urls[$row]} ..."
          open_in_browser "${service_urls[$row]}"
        elif [[ "$col" -eq 1 ]]; then
          echo "Streaming logs for ${service_containers[$row]} (Ctrl+C to stop)..."
          docker logs -f "${service_containers[$row]}"
          echo
          read -rsp "Press any key to return to menu..." -n1
        else
          echo "Entering container ${service_containers[$row]} (type 'exit' to return)..."
          docker exec -ti "${service_containers[$row]}" sh
          echo
          read -rsp "Press any key to return to menu..." -n1
        fi
        ;;
      $'\x1b')
        IFS= read -rsn2 key || true
        case "$key" in
          "[A") [[ "$row" -gt 0 ]] && row=$((row - 1)) ;;
          "[B") [[ "$row" -lt $((${#service_labels[@]} - 1)) ]] && row=$((row + 1)) ;;
          "[C") [[ "$col" -lt 2 ]] && col=$((col + 1)) ;;
          "[D") [[ "$col" -gt 0 ]] && col=$((col - 1)) ;;
        esac
        ;;
    esac
  done
}

discover_services

if [[ "${#service_labels[@]}" -eq 0 ]]; then
  echo "No supported dev services found."
  echo "Expected:"
  echo "- MinIO console (container port 9001 mapped)"
  echo "- RabbitMQ management (container port 15672 mapped)"
  echo "- Keycloak on host port 8081"
  exit 1
fi

interactive_select
