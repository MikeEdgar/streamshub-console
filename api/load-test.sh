#!/bin/bash

set -euo pipefail

CLUSTER_ID=$(authcurl.sh -sg 'http://localhost:8080/api/kafkas?fields[kafkas]=' | jq -r '.data[0].id')
LIST_QUERY='fields[topics]=name,status,visibility,numPartitions,totalLeaderLogBytes,consumerGroups&sort=name'
LOGOUT='/dev/stderr'
DATOUT='load-test-results.txt'
NUM_PARTITIONS="9"

function create_topics {
  local start=${1}
  local end=${2}

  for (( t=${start}; t<=${end}; t++ )) ; do
    authcurl.sh -sg -XPOST \
      -H 'Content-type: application/json' \
      -d '{
        "meta": {
          "validateOnly": false
        },
        "data": {
          "type": "topics",
          "attributes": {
            "name": "'t-${t}'",
            "numPartitions": '${NUM_PARTITIONS}',
            "replicationFactor": 3
          }
        }
      }' http://localhost:8080/api/kafkas/${CLUSTER_ID}/topics >/dev/null
    echo "Created topic t-${t}" >>${LOGOUT}
  done
}

function list_loop {
  local CNT="0"
  local BEGIN=$(date +"%s.%3N")
  local ITERS=50

  for (( i=1; i<=${ITERS}; i++ )) ; do
    CNT=$(authcurl.sh -sg 'http://localhost:8080/api/kafkas/'${CLUSTER_ID}'/topics?'${LIST_QUERY} | jq -r .meta.page.total)
    echo "Test iteration ${i}" >>${LOGOUT}
  done

  local END=$(date +"%s.%3N")
  local DUR=$(echo "${END}-${BEGIN}" | bc)
  local AVG=$(echo "scale=3 ; ${DUR}/${ITERS}" | bc)
  echo "$(date -Ins): ${CNT} topics(${NUM_PARTITIONS} partitions) in ${DUR}ms ; average ${AVG}s / request" >>${DATOUT}
}

function clean {
  local start=${1}
  local end=${2}

  for (( t=${start}; t<=${end}; t++ )) ; do
    ID=$(authcurl.sh -sg 'http://localhost:8080/api/kafkas/'${CLUSTER_ID}'/topics?filter[name]=t-'${t}'&fields[topics]=&page[size]=1' | jq -r .data[0].id)
    if [ -n "${ID}" ] && [ "${ID}" != "null" ] ; then
      authcurl.sh -sg "http://localhost:8080/api/kafkas/${CLUSTER_ID}/topics/${ID}" -XDELETE
      echo "Deleted topic t-${t}, id=${ID}" >>${LOGOUT}
    else
      echo "Topic t-${t} not found, skipping" >>${LOGOUT}
    fi
  done
}

BEFORE_CNT="$(authcurl.sh -sg http://localhost:8080/api/kafkas/${CLUSTER_ID}/topics?filter[name]=like,t-* | jq -r .meta.page.total)"

if [ "${BEFORE_CNT:-x}" != "0" ] ; then
    echo "Non-zero topic count before process: ${BEFORE_CNT}" >>${LOGOUT}
    exit 1
fi

echo "$(date -Ins): Begin" >>${DATOUT}

create_topics 1 10
list_loop >>${DATOUT}

create_topics 11 20
list_loop >>${DATOUT}

create_topics 21 50
list_loop >>${DATOUT}

create_topics 51 100
list_loop >>${DATOUT}

create_topics 101 200
list_loop >>${DATOUT}

create_topics 201 300
list_loop >>${DATOUT}

create_topics 301 400
list_loop >>${DATOUT}

create_topics 401 500
list_loop >>${DATOUT}

clean 1 500

echo "$(date -Ins): End" >>${DATOUT}
