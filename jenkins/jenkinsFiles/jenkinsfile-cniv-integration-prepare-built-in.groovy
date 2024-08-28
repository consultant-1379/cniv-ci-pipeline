@Library('ci-cn-pipeline-lib') _

env.bob = new BobCommand()
    .envVars([
        ISO_VERSION         : '${ISO_VERSION}',
        PRODUCT_SET         : '${PRODUCT_SET}',
        SPRINT_TAG          : '${SPRINT_TAG}',
        DOCKER_IMAGE_NAME   : '${DOCKER_IMAGE_NAME}',
        HELM_CHART_NAME     : '${DOCKER_IMAGE_NAME}'
    ])
    .needDockerSocket(true)
    .toString()

def bobInCA = new BobCommand()
    .needDockerSocket(true)
    .envVars([
        ARTIFACTORY_PSW       : '${CREDENTIALS_SELI_ARTIFACTORY_PSW}',
        HELM_INTERNAL_REPO    : '${HELM_INTERNAL_REPO}',
        HELM_DROP_REPO        : '${HELM_DROP_REPO}',
        HELM_RELEASED_REPO    : '${HELM_RELEASED_REPO}',
        CHART_NAME            : '${CHART_NAME}',
        CHART_VERSION         : '${CHART_VERSION}',
        GERRIT_USERNAME       : '${GERRIT_CREDENTIALS_USR}',
        GERRIT_PASSWORD       : '${GERRIT_CREDENTIALS_PSW}',
        GERRIT_REFSPEC        : '${GERRIT_REFSPEC}',
        GIT_BRANCH            : '${BRANCH}',
        HELM_REPO_CREDENTIALS : '${HELM_REPO_CREDENTIALS}'
    ])
    .toString()

pipeline {
    agent {
        label 'Cloud-Native'
    }
    environment {
        GERRIT_CREDENTIALS = credentials('cenmbuild_gerrit_api_token')
        CREDENTIALS_SELI_ARTIFACTORY = credentials('lciadm100ArtifactoryAccessTokenSELI')

        GIT_REPO_URL = "${GERRIT_CENTRAL_HTTP}/a/${GIT_REPO_PATH}"

        HELM_INTERNAL_REPO = "https://arm.epk.ericsson.se/artifactory/${PROJECT_SUBPATH}-ci-internal-helm/"
        HELM_DROP_REPO = "https://arm.epk.ericsson.se/artifactory/${PROJECT_SUBPATH}-drop-helm/"
        HELM_RELEASED_REPO = "https://arm.epk.ericsson.se/artifactory/${PROJECT_SUBPATH}-released-helm/"
    }
    stages {
        stage('Clean workspace') {
            steps {
                deleteDir()
            }
        }
        stage('Prepare') {
            steps {
                script {
                    if (env.GERRIT_REFSPEC) {
                        checkout changelog: true, \
                        scm: [$class: 'GitSCM', \
                        branches: [[name: '${GERRIT_REFSPEC}']], \
                        gitTool: "${GIT_TOOL}", \
                        doGenerateSubmoduleConfigurations: false, \
                        extensions: [[$class: 'BuildChooserSetting', buildChooser: [$class: 'GerritTriggerBuildChooser']]], \
                        submoduleCfg: [], \
                        userRemoteConfigs: [[refspec: '${GERRIT_REFSPEC}', \
                        url: '${GERRIT_MIRROR}/${GIT_REPO_PATH}']]]
                    } else {
                        checkout changelog: true, \
                        scm: [$class: 'GitSCM', \
                        branches: [[name: '${BRANCH}']], \
                        gitTool: "${GIT_TOOL}", \
                        extensions: [[$class: 'SubmoduleOption', disableSubmodules: false, parentCredentials: true, recursiveSubmodules: true, reference: '', trackingSubmodules: false], [$class: 'CleanBeforeCheckout']], \
                        userRemoteConfigs: [[url: '${GERRIT_MIRROR}/${GIT_REPO_PATH}']]]
                    }
                }
                sh 'mkdir config'
                sh 'FILE=jenkins/cnivFiles/ruleset2.0.yaml && git archive --remote=${GERRIT_MIRROR}/OSS/ENM-Parent/SQ-Gate/com.ericsson.oss.containerisation/cniv-ci-pipeline HEAD "$FILE" | tar -xO "$FILE" >  ruleset2.0.yaml'
            }
        }
        stage('Init') {
            steps {
                script {
                    sh "mkdir -p .bob/" //making the folder which contains the env vars

                    sh "echo ${GIT_REPO_URL} >.bob/var.git-repo-url"
                    sh "echo ${CHART_PATH} >.bob/var.integration-chart-path"
                }
            }
        }
        stage('Lint') {
            steps {
                parallel(
                    "lint helm": {
                        sh "${bob} lint:helm || true" // '|| true' TO BE REMOVED
                    },
                    "lint helm design rule checker": {
                        sh "${bob} lint:helm-chart-check || true" // '|| true' TO BE REMOVED
                    },
                )
            }
            post {
                always {
                    archiveArtifacts allowEmptyArchive: true, artifacts: '**/design-rule-check-report.*'
                }
            }
        }
        stage('Prepare Helm Chart') {
            steps {
                script {
                    withCredentials([file(credentialsId: 'cihelm_repo_credentials', variable: 'HELM_REPO_CREDENTIALS')]) {
                        writeFile file: 'repositories.yaml', text: readFile(HELM_REPO_CREDENTIALS)
                    }
                    sh "${bobInCA} prepare"
                }
                archiveArtifacts 'artifact.properties'
            }
        }
    }
    post {
        always {
            script {
                sh "docker system prune --volumes --all --force" // Remove all unused containers, networks, images (both dangling and unreferenced), and volumes
            }
        }
    }
}