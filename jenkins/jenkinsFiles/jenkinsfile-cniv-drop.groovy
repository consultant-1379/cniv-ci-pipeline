@Library('ci-cn-pipeline-lib') _
@Library('ci-cniv-pipeline-lib') cniv

env.bob = new BobCommand()
    .rulesetFile('rulesetCniv.yaml')
    .envVars([
        ARTIFACTORY_USR     : '${CREDENTIALS_SELI_ARTIFACTORY_USR}',
        ARTIFACTORY_PSW     : '${CREDENTIALS_SELI_ARTIFACTORY_PSW}',
        K8NAMESPACE         : '${K8NAMESPACE}',
        STORAGE_CLASS_NAME  : '${STORAGE_CLASS_NAME}',
        POM_FILE_PATH       : '${POM_FILE_PATH}',
        POM_FILE_PATH       : '${POM_FILE_PATH}',
        PROJECT_SUBPATH     : '${PROJECT_SUBPATH}'
    ])
    .needDockerSocket(true)
    .toString()

pipeline {
    agent {
        node {
            label "Cloud-Native"
        }
    }
    options {
        timestamps()
        timeout(time: 30, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '50', artifactNumToKeepStr: '50'))
    }
    environment {
        CREDENTIALS_SELI_ARTIFACTORY = credentials('lciadm100ArtifactoryAccessTokenSELI')
        DEFENSICS_HOME = '/home/lciadm100'
        DEFENSICS_ENABLED = "false"
        ZAP_ENABLED = "false"
        TENABLE_ENABLED = "false"
        NMAP_ENABLED = "false"
        KUBEBENCH_ENABLED = "false"
        KUBEHUNTER_ENABLED = "false"
        KUBEAUDIT_ENABLED = "true"
        KUBESEC_ENABLED = "true"
        TRIVY_ENABLED = "true"
        XRAY_ENABLED = "true"
        ANCHORE_ENABLED = "true"
    }
    stages {
        stage('Clean') {
            steps {
                deleteDir()
            }
        }
        stage('Prepare') {
            steps {
                ci_cniv_checkGerritSync()
                checkout changelog: true, \
                scm: [$class: 'GitSCM', \
                branches: [[name: '${BRANCH}']], \
                gitTool: "${GIT_TOOL}", \
                extensions: [[$class: 'SubmoduleOption', disableSubmodules: false, parentCredentials: true, recursiveSubmodules: true, reference: '', trackingSubmodules: false], [$class: 'CleanBeforeCheckout']], \
                userRemoteConfigs: [[url: '${GERRIT_MIRROR}/${GIT_REPO_PATH}']]]

                sh 'FILE=jenkins/cnivFiles/ruleset2.0.yaml && git archive --remote=${GERRIT_MIRROR}/OSS/ENM-Parent/SQ-Gate/com.ericsson.oss.containerisation/cniv-ci-pipeline HEAD "$FILE" | tar -xO "$FILE" >  rulesetCniv.yaml'
                stash includes: "*", name: "rulesetCniv.yaml", allowEmpty: true
            }
        }

        stage('Inject Credential Files') {
            steps {
                withCredentials([file(credentialsId: 'lciadm100-docker-auth', variable: 'dockerConfig')]) {
                    sh "install -m 600 ${dockerConfig} ${HOME}/.docker/config.json"
                }
            }
        }
        stage('Init') {
            steps {
                script {
                    sh "mkdir -p .bob/" //making the folder which contains the env var called git-repo-url
                    sh "mkdir -p ${WORKSPACE}/config/" //making the folder which contains configuration file for VA tools
                    sh "echo ${DOCKER_IMAGE_NAME} >.bob/var.docker-image-name"
                    sh "echo ${DOCKER_IMAGE_NAME} >.bob/var.helm-chart-name"
                    sh "echo ${CHART_PATH} >.bob/var.integration-chart-path"
                    sh "echo ${IMAGE_PRODUCT_NUMBER} >.bob/var.image-product-number"
                    sh "touch .bob/var.va-report-arguments"

                    env.BUILD_IMAGE = fileExists "Dockerfile"
                    env.BUILD_HELM = fileExists "${CHART_PATH}/Chart.yaml"
                    ci_cniv_get_common_properties_values()
                    authorName = sh(returnStdout: true, script: 'git show -s --pretty=%an')
                    currentBuild.displayName = currentBuild.displayName + ' / ' + authorName
                }
                sh "${bob} init-drop"
            }
        }
        stage('Helm Dep Up') {
            when {
                expression { env.BUILD_HELM == 'true' }
            }
            steps {
                withCredentials([file(credentialsId: 'cihelm_repo_credentials', variable: 'HELM_REPO_CREDENTIALS')]) {
                    writeFile file: 'repositories.yaml', text: readFile(HELM_REPO_CREDENTIALS)
                }
                sh "${bob} helm-dep-up"
            }
            post {
                failure {
                    script {
                        failedStage = env.STAGE_NAME
                    }
                }
            }
        }
        stage('Python Lint') {
            when {
                expression { env.PYTHON_LINT == 'true' }
            }
            steps {
                sh "${bob} set-working-directory"
                sh "${bob} pycodestyle"
                sh "${bob} pylint || true"
                archiveArtifacts 'pycodestyle.log'
            }
        }
        stage('Unit Test') {
            when {
                expression { env.UNIT_TEST == 'true' }
            }
            steps {
                sh "${bob} set-working-directory"
                sh "${bob} unit-tests"
                archiveArtifacts 'unittest.log'
            }
        }
        stage('Lint Go') {
            when {
                expression { env.BUILD_GO == 'true' }
            }
            steps {
                sh "${bob} lint:go"
            }
            post {
                always {
                    archiveArtifacts allowEmptyArchive: true, artifacts: '.bob/go-checkstyle.xml'
                }
            }
        }
        stage('Lint Helm') {
            steps {
                parallel(
                    "lint helm": {
                        sh "${bob} lint:helm || true" // '|| true' TO BE REMOVED
                    },
                    "lint helm design rule checker": {
                        sh "${bob} lint:helm-chart-check || true" // '|| true' TO BE REMOVED
                    },
                    /*"lint metrics": {
                        sh "${bob} lint:metrics-check" // MISSING SCRIPTS
                    },*/
                )
            }
            post {
                always {
                    archiveArtifacts allowEmptyArchive: true, artifacts: '**/design-rule-check-report.*'
                }
            }
        }
        stage('Lint Docker') {
            when {
                expression { env.BUILD_IMAGE == 'true' }
            }
            steps {
                sh '''
                    mkdir -p "${PWD}/build/hadolint-reports/"
                    chmod 760 "${PWD}/build/hadolint-reports/"
                    FILE=jenkins/cnivFiles/va/hadolint_scan_config.yaml && git archive --remote=${GERRIT_MIRROR}/OSS/ENM-Parent/SQ-Gate/com.ericsson.oss.containerisation/cniv-ci-pipeline HEAD "$FILE" | tar -xO "$FILE" >  config/hadolint_scan_config.yaml
                '''
                sh "${bob} lint:hadolint-scan"
                sh "echo -n ' --hadolint-reports build/va-reports/hadolint-reports' >>.bob/var.va-report-arguments"
            }
            post {
                always {
                    archiveArtifacts allowEmptyArchive: true, artifacts: '**/hadolint_data*'
                }
            }
        }
        stage('Maven Unit Test') {
            when {
                expression { env.MAVEN_TEST == 'true' }
            }
            steps {
                sh "${bob} maven-unit-test"
            }
        }
        stage('Go Build') {
            when {
                expression { env.BUILD_GO == 'true' }
            }
            steps {
                sh "${bob} build-go"
            }
        }
        stage('Helm Build') {
            when {
                expression { env.BUILD_HELM == 'true' }
            }
            steps {
                sh "${bob} build-helm"
            }
        }
        stage('Image Build') {
            when {
                expression { env.BUILD_IMAGE == 'true' }
            }
            steps {
                ci_cniv_docker_image_build_and_publish("build-image")
                sh "${bob} image-dr-check || true" // || true to be removed
            }
            post {
                always {
                    archiveArtifacts allowEmptyArchive: true, artifacts: '**/image-design-rule-check-report*'
                }
            }
        }
        stage('Package Go') {
            when {
                expression { env.BUILD_GO == 'true' }
            }
            steps {
                sh "${bob} publish-go:rename-go-package publish-go:go-upload-internal"
            }
        }
        stage('Package Helm') {
            when {
                expression { env.BUILD_HELM == 'true' }
            }
            steps {
                sh "${bob} package:helm-upload-internal"
            }
        }
        stage('Package Image') {
            when {
                expression { env.BUILD_IMAGE == 'true' }
            }
            steps {
                ci_cniv_docker_image_build_and_publish("package-image")
            }
            post {
                cleanup {
                    sh "${bob} delete-images:delete-internal-image"
                }
            }
        }
        stage('Vulnerability Analysis') {
            steps {
                parallel(
                    "Kubeaudit": {
                        script {
                            if (env.BUILD_HELM == "true" && env.KUBEAUDIT_ENABLED == "true") {
                                sh 'FILE=jenkins/cnivFiles/va/kubeaudit_config.yaml && git archive --remote=${GERRIT_MIRROR}/OSS/ENM-Parent/SQ-Gate/com.ericsson.oss.containerisation/cniv-ci-pipeline HEAD "$FILE" | tar -xO "$FILE" >  config/kubeaudit_config.yaml'
                                sh "sed -i 's#PRODUCT_CONTACT#${env.PRODUCT_CONTACT}#g' config/kubeaudit_config.yaml"
                                sh "sed -i 's#PRODUCT#${env.DOCKER_IMAGE_NAME}#g' config/kubeaudit_config.yaml"
                                sh "${bob} kube-audit"
                                sh "echo -n ' --kubeaudit-reports build/va-reports/kube-audit-report' >>.bob/var.va-report-arguments"
                                archiveArtifacts allowEmptyArchive: true, artifacts: "build/va-reports/kube-audit-report/**/*"
                            }
                        }
                    },
                    "Kubsec": {
                        script {
                            if (env.BUILD_HELM == "true" && env.KUBESEC_ENABLED == "true") {
                                sh 'FILE=jenkins/cnivFiles/va/kubesec_config.yaml && git archive --remote=${GERRIT_MIRROR}/OSS/ENM-Parent/SQ-Gate/com.ericsson.oss.containerisation/cniv-ci-pipeline HEAD "$FILE" | tar -xO "$FILE" >  config/kubesec_config.yaml'
                                sh "sed -i 's#PRODUCT_CONTACT#${env.PRODUCT_CONTACT}#g' config/kubesec_config.yaml"
                                sh "sed -i 's#PRODUCT#${env.DOCKER_IMAGE_NAME}#g' config/kubesec_config.yaml"
                                sh "${bob} kubesec-scan"
                                sh "echo -n ' --kubesec-reports build/va-reports/kubesec-reports' >>.bob/var.va-report-arguments"
                                archiveArtifacts allowEmptyArchive: true, artifacts: 'build/va-reports/kubesec-reports/*'
                            }
                        }
                    },
                    "Trivy": {
                        script {
                            if (env.BUILD_IMAGE == "true" && env.TRIVY_ENABLED == "true") {
                                sh "${bob} trivy-inline-scan"
                                sh "echo -n ' --trivy-reports build/va-reports/trivy-reports' >>.bob/var.va-report-arguments"
                                archiveArtifacts allowEmptyArchive: true, artifacts: 'build/va-reports/trivy-reports/**.*'
                                archiveArtifacts allowEmptyArchive: true, artifacts: 'trivy_metadata.properties'
                            }
                        }
                    },
                    "X-Ray": {
                        script {
                            if (env.BUILD_IMAGE == "true" && env.XRAY_ENABLED == "true") {
                                sh 'FILE=jenkins/cnivFiles/va/xray_report.config && git archive --remote=${GERRIT_MIRROR}/OSS/ENM-Parent/SQ-Gate/com.ericsson.oss.containerisation/cniv-ci-pipeline HEAD "$FILE" | tar -xO "$FILE" >  config/xray_report.config'
                                sh "${bob} fetch-xray-report"
                                sh "echo -n ' --xray build/va-reports/xray-reports/xray_report.json' >>.bob/var.va-report-arguments"
                                archiveArtifacts allowEmptyArchive: true, artifacts: 'build/va-reports/xray-reports/xray_report.json'
                                archiveArtifacts allowEmptyArchive: true, artifacts: 'build/va-reports/xray-reports/raw_xray_report.json'
                            }
                        }
                    },
                    "Anchore-Grype": {
                        script {
                            if (env.BUILD_IMAGE == "true" && env.ANCHORE_ENABLED == "true") {
                                sh "${bob} anchore-grype-scan"
                                sh "echo -n ' --anchore-reports build/va-reports/anchore-reports' >>.bob/var.va-report-arguments"
                                archiveArtifacts allowEmptyArchive: true, artifacts: 'build/va-reports/anchore-reports/**.*'
                            }
                        }
                    }
                )
            }
        }
        stage('Helm Install') {
            when {
                expression { env.HELM_INSTALL_DROP == 'true' }
            }
            steps {
                script {
                    sh 'FILE=jenkins/cnivFiles/healthcheck.sh && git archive --remote=${GERRIT_MIRROR}/OSS/ENM-Parent/SQ-Gate/com.ericsson.oss.containerisation/cniv-ci-pipeline HEAD | tar -xO "$FILE" >  healthcheck.sh'
                    sh 'chmod +x healthcheck.sh'
                    withCredentials([file(credentialsId: K8SCLUSTERID , variable: 'KUBECONFIG')]) {
                        writeFile file: '.kube/config', text: readFile(KUBECONFIG)
                    }
                    if (env.HELM_TEARDOWN_BEFORE == 'true') {
                        sh "${bob} helm-teardown"
                    }
                    sh "${bob} helm-install"
                    sleep(time: env.HELM_INSTALL_PAUSE, unit: 'MINUTES')
                    sh "${bob} healthcheck"
                    if (env.HELM_TEARDOWN_AFTER == 'true') {
                        sh "${bob} helm-teardown"
                    }
                }
            }
            post {
                always {
                    archiveArtifacts allowEmptyArchive: true, artifacts: 'pods.log'
                }
            }
        }
        stage('Kubehunter') {
            when {
                expression { env.KUBEHUNTER_ENABLED == "true" }
            }
            steps {
                script {
                    sh 'FILE=jenkins/cnivFiles/va/kubehunter_config.yaml && git archive --remote=${GERRIT_MIRROR}/OSS/ENM-Parent/SQ-Gate/com.ericsson.oss.containerisation/cniv-ci-pipeline HEAD "$FILE" | tar -xO "$FILE" >  config/kubehunter_config.yaml'
                    sh "${bob} get-node-ip"
                    def IP_ADDRESS = sh(script: "cat .bob/var.node-ip", returnStdout: true)
                    IP_ADDRESS = IP_ADDRESS.replaceAll("\\s","")
                    sh "sed -i 's#PRODUCT_CONTACT#${env.PRODUCT_CONTACT}#g' config/kubehunter_config.yaml"
                    sh "sed -i 's#PRODUCT#${env.DOCKER_IMAGE_NAME}#g' config/kubehunter_config.yaml"
                    sh "sed -i 's#IP_ADDRESS#${IP_ADDRESS}#g' config/kubehunter_config.yaml"
                    sh "${bob} kube-hunter"
                }
            }
        }
        stage('Kubebench') {
            when {
                expression { env.KUBEBENCH_ENABLED == "true" }
            }
            steps {
                script {
                    sh 'FILE=jenkins/cnivFiles/va/kubebench_config.yaml && git archive --remote=${GERRIT_MIRROR}/OSS/ENM-Parent/SQ-Gate/com.ericsson.oss.containerisation/cniv-ci-pipeline HEAD "$FILE" | tar -xO "$FILE" >  config/kubebench_config.yaml'     
                    sh "sed -i 's#K8NAMESPACE#${env.K8NAMESPACE}#g' config/kubebench_config.yaml"
                    sh "sed -i 's#PRODUCT_CONTACT#${env.PRODUCT_CONTACT}#g' config/kubebench_config.yaml"
                    sh "sed -i 's#PRODUCT#${env.DOCKER_IMAGE_NAME}#g' config/kubebench_config.yaml"
                    sh "${bob} kubebench-scan"
                }

            }
        }
        stage('NMap') {
            when {
                expression { env.NMAP_ENABLED == "true" }
            }
            steps {
                script {
                    sh "${bob} nmap-cleanup"
                    //podChunkSize will run scans in chunks. otherwise all containers will be scanned at once.
                    def podChunkSize = 40
                    echo "Scanning all containers"
                    sh "${bob} get-pods"
                    services = readFile(file: '.bob/var.pods').split('\n')
                    servicesCollated = services.toList().collate(podChunkSize)

                    servicesCollated.eachWithIndex { val, idx ->
                        println "$val in position $idx"
                        env.FILENAME = "nmapConfigFile_" + idx
                        sh "echo 'nmapConfig:'>${FILENAME}"
                        sh "    echo '  services:'>>${FILENAME}"

                        for (service in val) {
                            env.SERVICE = service
                            sh "cat ./cENM/nmap/nmap_template.yaml |sed -e 's/SERVICE_NAME/${SERVICE}/'>>${FILENAME}"
                            sh "echo ''>>${FILENAME}"
                        }
                        sh "echo '  reportDir : \"nmap_reports\"'>>${FILENAME}"
                    }
                    servicesCollated.eachWithIndex { val, idx ->
                        env.FILENAME = "nmapConfigFile_" + idx
                        sh """
                            cp ${FILENAME} nmap_config.yaml
                            ${bob} nmap-port-scanning
                        """
                    }
                }
            }
        }
        stage('Tenable') {
            when {
                expression { env.TENABLE_ENABLED == "true" }
            }
            steps {
                script {
                    withCredentials([file(credentialsId: "tenable-secrets" , variable: 'tenablescsecrets')]) {
                        writeFile file: "tenablesc-secrets.yaml", text: readFile(tenablescsecrets)
                    }
                    withCredentials([file(credentialsId: "tenable-nessus-sc" , variable: 'nessussc')]) {
                        writeFile file: "nessus-sc.conf", text: readFile(nessussc)
                    }
                    sh "${bob} get-pod-ip"
                    sh "${bob} tenable-scanning"
                }
            }
        }
        stage('Zap') {
            when {
                expression { env.ZAP_ENABLED == "true" }
            }
            steps {
                script {
                    sh 'FILE=jenkins/cnivFiles/va/zap_config.yaml && git archive --remote=${GERRIT_MIRROR}/OSS/ENM-Parent/SQ-Gate/com.ericsson.oss.containerisation/cniv-ci-pipeline HEAD "$FILE" | tar -xO "$FILE" >  config/zap_config.yaml'
                    sh "${bob} zap-cleanup"
                    sh "sed -i 's#BASE_URL#${ZAP_BASE_URL}#' zap_config.yaml"
                    sh "${bob} zap-scan || true"
                }
            }
        }
        stage('Defensics') {
            when {
                expression { env.DEFENSICS_ENABLED == "true" }
            }
            steps {
                script {
                    sh '''
                        docker run --rm --user 1001:1001 --env DEFENSICS_HOME=${DEFENSICS_HOME} -w ${DEFENSICS_HOME} -v ${DEFENSICS_HOME}:${DEFENSICS_HOME} -v ::rw armdocker.rnd.ericsson.se/proj-adp-cicd-drop/defensics.cbo:latest run-defensics -s ${DEFENSICS_TESTSUITE_NAME} -t ${DEFENSICS_TESTPLAN_DIR} -r ${WORKSPACE}/ -p "--${DEFENSICS_PROPERTY}"
                    '''
                }
            }
        }
        stage('Generate ADP Parameters') {
            steps {
                sh "${bob} generate-output-parameters-drop-stage"
                archiveArtifacts 'artifact.properties'
            }
            post {
                failure {
                    script {
                        failedStage = env.STAGE_NAME
                    }
                }
            }
        }
        stage('Publish Helm') {
            when {
                expression { env.BUILD_HELM == 'true'}
            }
            steps {
                sh "${bob} publish:helm-upload"
            }
        }
        stage('Publish Image') {
            when {
                expression { env.BUILD_IMAGE == 'true'}
            }
            steps {
                ci_cniv_docker_image_build_and_publish("publish-image")
            }
            post {
                cleanup {
                    sh "${bob} delete-images"
                }
            }
        }
        stage('Publish Go') {
            when {
                expression { env.BUILD_GO == 'true' }
            }
            steps {
                sh "${bob} publish-go:rename-go-package publish-go:go-upload"
            }
        }
        stage('Overall VA Report') {
            steps {
                script {
                    sh '''
                        echo 'model_version: 2.0' >va_report_config.yaml
                        echo 'product_va_config:' >>va_report_config.yaml
                        echo '    name: CNIV' >>va_report_config.yaml
                        echo '    product_name: CNIV' >>va_report_config.yaml
                        echo '    version: ' >>va_report_config.yaml
                        echo '    va_template_version: 2.0.0' >>va_report_config.yaml
                        echo '    description: CNIV vulnerability analysis report' >>va_report_config.yaml
                    '''
                    sh "${bob} generate-VA-report-V2 || true" // 'GATING', TO BE REMOVED
                }
            }
            post {
                always {
                    script {
                        try {
                            sh '''
                                git clone ${GERRIT_MIRROR}/adp-fw/adp-fw-templates
                                cd adp-fw-templates
                                cp ../Vulnerability_Report_2.0.md .
                                git submodule update --init --recursive
                                sed -i s#user-guide-template/user_guide_template.md#Vulnerability_Report_2.0.md#g marketplace-config.yaml
                            '''
                            dir("adp-fw-templates") {
                                unstash "rulesetCniv.yaml"
                            }
                            sh "cd adp-fw-templates;${bob} clean init lint-md"
                            sh "cd adp-fw-templates;${bob} generate-docs"

                            archiveArtifacts artifacts: 'Vulnerability_Report_2.0.md', allowEmptyArchive: true
                            archiveArtifacts artifacts: 'adp-fw-templates/build/marketplace/html/user-guide-template/Vulnerability_Report_2.0.html', allowEmptyArchive: true

                            publishHTML (target: [
                                allowMissing: false,
                                alwaysLinkToLastBuild: false,
                                keepAll: true,
                                reportDir: 'adp-fw-templates/build/marketplace/html/user-guide-template',
                                reportFiles: 'Vulnerability_Report_2.0.html',
                                reportName: "VA Report"
                            ])
                        } catch (Exception e) {
                            echo 'Generating html report, Exception occurred: ' + e.toString()
                        }
                    }
                }
            }
        }
    }
    post {
        always {
            script {
                ci_cniv_auto_commit_check()
            }
        }
        success {
            script {
                sh """
                    TAG_ID=\$(cat .bob/var.version)
                    set +x
                    git remote set-url --push origin \${GERRIT_CENTRAL}/\${GIT_REPO_PATH}
                    git tag --annotate --message "Tagging latest" --force \${TAG_ID} HEAD
                    git push --force origin \${TAG_ID}
                """
                if (env.DOCKER_IMAGE_NAME == "eric-enm-pm-bench") {
                    ci_cniv_pm_bench_version_update()
                }
            }
        }
        failure {
            script {
                if (AUTOMATIC_REVIEW.toBoolean()) {
                    ci_cniv_auto_send_email()
                }
            }
        }
    }
}