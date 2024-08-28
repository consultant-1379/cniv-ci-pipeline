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
        KUBEAUDIT_ENABLED = "true"
        KUBESEC_ENABLED = "true"
        TRIVY_ENABLED = "true"
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
                checkout changelog: true, \
                scm: [$class: 'GitSCM', \
                branches: [[name: '${GERRIT_REFSPEC}']], \
                gitTool: "${GIT_TOOL}", \
                doGenerateSubmoduleConfigurations: false, \
                extensions: [[$class: 'BuildChooserSetting', buildChooser: [$class: 'GerritTriggerBuildChooser']]], \
                submoduleCfg: [], \
                userRemoteConfigs: [[refspec: '${GERRIT_REFSPEC}', \
                url: '${GERRIT_MIRROR}/${GIT_REPO_PATH}']]]

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
                sh "${bob} init-precodereview"
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
                                sh "sed -i 's#PRODUCT_CONTACT#${env.PRODUCT_CONTACT}#g' config/kubeaudit_config.yaml"
                                sh "sed -i 's#PRODUCT#${env.DOCKER_IMAGE_NAME}#g' config/kubeaudit_config.yaml"
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
                expression { env.HELM_INSTALL == 'true' }
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
        stage('Generate ADP Parameters') {
            steps {
                sh "${bob} generate-output-parameters-internal-stage"
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
                        } finally{
                            deleteDir()
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
                if (AUTOMATIC_REVIEW.toBoolean()) {
                    sh '''
                        GERRIT_HOST=$(echo ${GERRIT_CENTRAL} | sed "s/ssh:\\/\\///;s/\\:29418$//")
                        ssh -p 29418 ${GERRIT_HOST} gerrit review \
                        --verified +1 \
                        --code-review +2 \
                        --submit \
                        --project "${GERRIT_PROJECT}" \
                        "${GERRIT_CHANGE_NUMBER},${GERRIT_PATCHSET_NUMBER}"
                    '''
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