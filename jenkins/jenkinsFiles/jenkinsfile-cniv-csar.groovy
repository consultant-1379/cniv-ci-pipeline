@Library('ci-cn-pipeline-lib') _

env.bob = new BobCommand()
    .needDockerSocket(true)
    .toString()

def defaultAMPackageImage = 'armdocker.rnd.ericsson.se/proj-am/releases/eric-am-package-manager:latest'
env.bobCSAR = new BobCommand()
    .bobImage(defaultAMPackageImage)
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
    environment{
        repositoryUrl = "https://arm1s11-eiffel004.eiffel.gic.ericsson.se:8443/nexus/content/repositories/cniv-releases/"
        CSAR_CNIV_PACKAGE_NAME = "enm-cniv-installation-package"
        PACKAGE_TYPE = "csar"
    }
    stages {
        stage('Clean') {
            steps {
                deleteDir()
            }
        }
        stage('Import Local Variables') {
            steps {
                script {
                    if (env.PIPELINE_LOCAL_VARIABLES) {
                        ARRAY_LOCAL_VARIABLE = PIPELINE_LOCAL_VARIABLES.trim().tokenize("\n")
                        ARRAY_LOCAL_VARIABLE.each {
                            def (env_name, env_value) = it.split('=', 2)
                            env."$env_name"="$env_value"
                        }
                    }
                }
            }
        }
        stage('Prepare') {
            steps {
                checkout changelog: true, \
                scm: [$class: 'GitSCM', \
                branches: [[name: 'master']], \
                gitTool: "${GIT_TOOL}", \
                extensions: [[$class: 'SubmoduleOption', disableSubmodules: false, parentCredentials: true, recursiveSubmodules: true, reference: '', trackingSubmodules: false], [$class: 'CleanBeforeCheckout']], \
                userRemoteConfigs: [[url: '${GERRIT_MIRROR}/${GIT_REPO_PATH}']]]

                sh 'FILE=jenkins/csarFile/upload_to_nexus.sh && git archive --remote=${GERRIT_MIRROR}/OSS/ENM-Parent/SQ-Gate/com.ericsson.oss.containerisation/cniv-ci-pipeline HEAD "$FILE" | tar -xO "$FILE" >  upload_to_nexus.sh'
                sh 'FILE=jenkins/csarFile/ruleset2.0.yaml && git archive --remote=${GERRIT_MIRROR}/OSS/ENM-Parent/SQ-Gate/com.ericsson.oss.containerisation/cniv-ci-pipeline HEAD "$FILE" | tar -xO "$FILE" >  ruleset2.0.yaml'
            }
        }
        stage('Inject Credential Files') {
            steps {
                withCredentials([file(credentialsId: 'lciadm100-docker-auth', variable: 'dockerConfig')]) {
                    sh "install -m 600 ${dockerConfig} ${HOME}/.docker/config.json"
                    sh "ls -la ${HOME}/.docker/"
                }
            }
        }
        stage('Get Integration Chart') {
            steps {
                script {
                    sh "mkdir charts"
                    withCredentials([usernamePassword(credentialsId: 'cenmbuildArtifactoryAccessTokenSELI', , usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) {
                        sh 'curl -u ${USERNAME}:${PASSWORD} https://arm.seli.gic.ericsson.se/artifactory/proj-eric-oss-cniv-drop-helm/eric-cniv-enm-integration-chart/${INT_CHART_NAME}-${INT_CHART_VERSION}.tgz -o charts/${INT_CHART_NAME}-${INT_CHART_VERSION}.tgz'
                    }
                }
            }
        }
        /* Placeholder, anyway it's not the best WoW.
        /*stage('Get Integration values files') {
            steps{
                script {
                    withCredentials([usernamePassword(credentialsId: 'enmadm100_JENKINS_token', usernameVariable: 'JENKINS_USER_NAME', passwordVariable: 'JENKINS_API_TOKEN')]) {
                        sh "curl -4 -u ${JENKINS_USER_NAME}:${JENKINS_API_TOKEN} https://fem35s11-eiffel004.eiffel.gic.ericsson.se:8443/jenkins/job/eric-cniv-enm-integration-values_Drop/lastBuild/artifact/artifact.properties > integration-values-version"
                    }

                    sh "grep CHART_VERSION=  integration-values-version | sed 's/CHART_VERSION=//' > INTEGRATION_VALUE_VERSION"
                    def INT_VALUE_VERSION = readFile('INTEGRATION_VALUE_VERSION')
                    env.INT_VALUE_VERSION=INT_VALUE_VERSION.trim()
                    if (env.INT_VALUE_VERSION == "" ) {
                        echo "Pipeline fails due to integration-values file is empty"
                        sh "exit 1"
                    }

                    sh 'curl -4 https://arm.epk.ericsson.se/artifactory/proj-eric-oss-cniv-drop-helm/eric-enm-cniv-integration-values/eric-cniv-enm-integration-extra-large-production-values-${INT_VALUE_VERSION}.yaml -o scripts/eric-cniv-enm-integration-extra-large-production-values-${INT_VALUE_VERSION}.yaml'
                    sh 'curl -4 https://arm.epk.ericsson.se/artifactory/proj-eric-oss-cniv-drop-helm/eric-enm-cniv-integration-values/eric-cniv-enm-integration-small-production-values-${INT_VALUE_VERSION}.yaml -o scripts/eric-cniv-enm-integration-small-production-values-${INT_VALUE_VERSION}.yaml'
                }
            }
        }*/
        stage('Get Integration values files') {
            steps{
                script {
                    sh 'curl -4 https://arm.epk.ericsson.se/artifactory/proj-eric-oss-cniv-drop-helm/eric-enm-cniv-integration-values/eric-cniv-enm-integration-extra-large-production-values-${INT_VALUE_VERSION}.yaml -o scripts/eric-cniv-enm-integration-extra-large-production-values-${INT_VALUE_VERSION}.yaml'
                    sh 'curl -4 https://arm.epk.ericsson.se/artifactory/proj-eric-oss-cniv-drop-helm/eric-enm-cniv-integration-values/eric-cniv-enm-integration-small-production-values-${INT_VALUE_VERSION}.yaml -o scripts/eric-cniv-enm-integration-small-production-values-${INT_VALUE_VERSION}.yaml'
                }
            }
        }
        stage('Generate new version') {
            steps {
                sh "${bob} generate-new-version"
                script {
                    env.VERSION = sh(script: "cat .bob/var.version", returnStdout:true).trim()
                    echo "${VERSION}"
                    env.RSTATE = sh(script: "cat .bob/var.rstate", returnStdout:true).trim()
                    echo "${RSTATE}"
                }
            }
        }
        stage('CSAR Build') {
            steps {
                sh "${bobCSAR} generate -hd charts/ --name ${CSAR_CNIV_PACKAGE_NAME}-${VERSION} --helm3 -f scripts/eric-cniv-enm-global-values.yaml --scripts scripts"
            }
        }
        stage('Publish Csar Package to Nexus') {
            steps {
                script {
                    sh "bash upload_to_nexus.sh ${VERSION} ${CSAR_CNIV_PACKAGE_NAME}-${VERSION}.csar ${repositoryUrl} ${CSAR_CNIV_PACKAGE_NAME} ${PACKAGE_TYPE}"
                }
            }
        }
    }
    post {
        always {
            script {
                sh "docker system prune --volumes --all --force" // Remove all unused containers, networks, images (both dangling and unreferenced), and volumes
            }
        }
        success {
            script {
                sh """
                    set +x
                    git remote set-url --push origin \${GERRIT_CENTRAL}/\${GIT_REPO_PATH}
                    git tag --annotate --message "Tagging latest" --force csar_\${VERSION} HEAD
                    git push --force origin csar_\${VERSION}
                """
            }
        }
    }
}