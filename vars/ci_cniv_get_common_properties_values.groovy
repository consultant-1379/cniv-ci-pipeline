#!/usr/bin/env groovy

def call() {
     env.PRODUCT_CONTACT = 'productspocemail@ericsson.com'
     env.K8NAMESPACE = ''
     env.K8SCLUSTERID = ''
     env.STORAGE_CLASS_NAME = ''
     env.HELM_INSTALL = 'false'
     env.HELM_INSTALL_DROP = 'false'
     env.HELM_INSTALL_PAUSE = 3
     env.HELM_TEARDOWN_BEFORE = 'true'
     env.HELM_TEARDOWN_AFTER = 'true'
     env.ZAP_BASE_URL = ''
     env.DEFENSICS_PROPERTY = ''
     env.DEFENSICS_TESTPLAN_DIR = ''
     env.DEFENSICS_TESTSUITE_NAME = ''
     def commonPropFileExists = fileExists "${WORKSPACE}/common-properties.yaml"
     if (commonPropFileExists == true) {
          def commonProp = readYaml file: "${WORKSPACE}/common-properties.yaml"
          def properties = commonProp['properties']
          for (int i = 0; i < properties.size(); i++) {
               for ( entry in properties[i] ) {
                    switch (entry.key) {
                         case "product-contact":
                              env.PRODUCT_CONTACT = entry.value
                              break
                         case "namespace":
                              env.K8NAMESPACE = entry.value
                              break
                         case "cluster-id":
                              env.K8SCLUSTERID = entry.value
                              break
                         case "storage-class-name":
                              env.STORAGE_CLASS_NAME = entry.value
                              break
                         case "helm-install":
                              env.HELM_INSTALL = entry.value
                              break
                         case "helm-install-drop":
                              env.HELM_INSTALL_DROP = entry.value
                              break
                         case "helm-install-pause":
                              env.HELM_INSTALL_PAUSE = entry.value as int
                              break
                         case "helm-teardown-before":
                              env.HELM_TEARDOWN_BEFORE = entry.value
                              break
                         case "helm-teardown-after":
                              env.HELM_TEARDOWN_AFTER = entry.value
                              break
                         case "zap-base-url":
                              env.ZAP_BASE_URL = entry.value
                              break
                         case "defensics-property":
                              env.DEFENSICS_PROPERTY = entry.value
                              break
                         case "defensics-testplan-dir":
                              env.DEFENSICS_TESTPLAN_DIR = entry.value
                              break
                         case "defensics-testsuite-name":
                              env.DEFENSICS_TESTSUITE_NAME = entry.value
                              break
                         default:
                              break
                    }
               }
          }
     }
}

