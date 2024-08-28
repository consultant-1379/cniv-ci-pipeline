@Library('ci-cn-pipeline-lib') _

env.bob = new BobCommand()
    .envVars([
        HELM_REPO_TOKEN: '${HELM_REPO_TOKEN}'
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
        HELM_REPO_TOKEN = credentials('cenmbuildArtifactoryAccessTokenSELI')
    }
    stages {
        stage('Clean') {
            steps {
                deleteDir()
            }
        }
        stage('Prepare') {
            steps {
                checkout([$class: 'GitSCM',
                    branches: [
                        [name: '${BRANCH}']
                    ],
                    extensions: [
                        [$class: 'SubmoduleOption',
                            disableSubmodules: false,
                            parentCredentials: true,
                            recursiveSubmodules: true,
                            reference: '',
                            trackingSubmodules: false],
                            [$class: 'CleanBeforeCheckout']
                        ],
                    userRemoteConfigs: [
                        [url: '${GERRIT_MIRROR}/${GIT_REPO_PATH}']
                    ]
                ])

                sh 'FILE=jenkins/cnivFiles/ruleset2.0.yaml && git archive --remote=${GERRIT_MIRROR}/OSS/ENM-Parent/SQ-Gate/com.ericsson.oss.containerisation/cniv-ci-pipeline HEAD "$FILE" | tar -xO "$FILE" >  ruleset2.0.yaml'
            }
        }
        stage('Inject Credential Files') {
            steps {
                withCredentials([file(credentialsId: 'lciadm100-docker-auth', variable: 'dockerConfig')]) {
                    sh "install -m 600 ${dockerConfig} ${HOME}/.docker/config.json"
                }
            }
        }
        stage('generate-new-version') {
            steps {
                sh "${bob} generate-new-version"
            }
            post {
                failure {
                    script {
                        failedStage = env.STAGE_NAME
                    }
                }
            }
        }
        stage('Build Integration Value') {
            steps {
                sh "${bob} build-integration-value"
            }
            post {
                failure {
                    script {
                        failedStage = env.STAGE_NAME
                    }
                }
            }
        }
        stage('Linting yaml') {
            steps {
                sh "${bob} yaml-validation"
            }
            post {
                failure {
                    script {
                        failedStage = env.STAGE_NAME
                    }
                }
            }
        }
        stage('Publish Integration Values') {
            steps {
                sh "${bob} push-integration-value"
            }
        }
        stage('Generate ADP Parameters') {
            steps {
                sh "${bob} generate-output-parameters-drop-stage"
                archiveArtifacts 'artifact.properties'
            }
        }
    }
}