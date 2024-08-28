#! /bin/bash
#
# COPYRIGHT Ericsson 2021
#
#
#
# The copyright to the computer program(s) herein is the property of
#
# Ericsson Inc. The programs may be used and/or copied only with written
#
# permission from Ericsson Inc. or in accordance with the terms and
#
# conditions stipulated in the agreement/contract under which the
#
# program(s) have been supplied.
#

count=0
while true; do
  echo "==============START OF CHECK $count================" | tee -a pods.log
  NOT_RUNNING=$(kubectl get pods -n $K8S_NAMESPACE --field-selector='status.phase!=Running' --no-headers | wc -l)
  RUNNING=$(kubectl get pods -n $K8S_NAMESPACE --field-selector='status.phase=Running' --no-headers | wc -l)
  ERROR=$(kubectl get pods -n $K8S_NAMESPACE | grep Error)
  COMPLETED=$(kubectl get pods -n $K8S_NAMESPACE | grep Completed)
  POD_INFO=$(kubectl get pods -n $K8S_NAMESPACE)
  POD_NAMES=($(kubectl get pods --no-headers -o custom-columns=":metadata.name" -n $K8S_NAMESPACE))

  echo "Pods not running in the namespace: ${NOT_RUNNING}"
  echo "Pods running in the namespace: ${RUNNING}"
  echo -e "PODs INFO:\n${POD_INFO}" | tee -a pods.log
  echo "PODs Log and Describe:" | tee -a pods.log
  for pod in "${POD_NAMES[@]}"; do
      POD_LOG=$(kubectl logs ${pod} -n $K8S_NAMESPACE)
      POD_DESCRIBE=$(kubectl describe pods ${pod} -n $K8S_NAMESPACE)
      echo -e "POD: ${pod}\nLOG:\n${POD_LOG}\nDESCRIBE:\n${POD_DESCRIBE}\n" | tee -a pods.log
  done
  if [[ ! -z "$ERROR" ]]; then
       echo "ERROR"; echo "Pod Information:"; echo "${POD_INFO}"
       exit 1
  fi
  if [[ "$NOT_RUNNING" -eq 0 && "$RUNNING" -gt 0 ]]; then
        echo "ALL PODS RUNNING"
        exit 0
  fi
  if [[ "$RUNNING" -eq 0 && ! -z "$COMPLETED" ]]; then
        echo "ALL PODS COMPLETED"
        exit 0
  fi
  if [[ "$count" -gt 5 ]]; then
       echo "TIMEOUT"; echo "Pod Information:"; echo "${POD_INFO}"
       exit 1
  fi
  echo "===============END OF CHECK $count=================" | tee -a pods.log
  sleep 15
  ((count++))
done