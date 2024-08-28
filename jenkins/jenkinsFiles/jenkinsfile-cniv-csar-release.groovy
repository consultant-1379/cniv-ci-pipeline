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
        PACKAGE_TYPE = "csar-released"
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
                sh '''
                git remote set-url origin --push ${GERRIT_CENTRAL}/${GIT_REPO_PATH}
                '''
                sh 'FILE=jenkins/csarFile/upload_to_nexus.sh && git archive --remote=${GERRIT_MIRROR}/OSS/ENM-Parent/SQ-Gate/com.ericsson.oss.containerisation/cniv-ci-pipeline HEAD "$FILE" | tar -xO "$FILE" >  upload_to_nexus.sh'
                script {
                    // Read the initial version from the VERSION_PREFIX file
                    def versionContent = readFile('VERSION_PREFIX').trim()
                    // Set the VERSION variable as an environment variable
                    env.VERSION = versionContent
                    // Echo the value of VERSION for verification
                    echo "Initial version: $VERSION"
               }
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
        stage('Get integration values files') {
            steps{
                script {
                    sh 'cp integration-values/eric-cniv-enm-integration-extra-large-production-values.yaml scripts/eric-cniv-enm-integration-extra-large-production-values.yaml'
                    sh 'cp integration-values/eric-cniv-enm-integration-small-production-values.yaml scripts/eric-cniv-enm-integration-small-production-values.yaml'
                }
            }
        }
        stage('CSAR Build') {
            steps {
                script{
                    def path = "${env.WORKSPACE}/scripts/eric-cniv-enm-global-values.yaml"
                    echo "The version is: ${env.VERSION}"
                    updateChart(path)
                    sh "${bobCSAR} generate -hd charts/ --name ${CSAR_CNIV_PACKAGE_NAME}-${VERSION} --helm3 -f scripts/eric-cniv-enm-global-values.yaml --scripts scripts"
                }
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
                // Increment the version and update the VERSION_PREFIX file
                def newVersion = incrementVersion()
                echo "newVersion: ${newVersion}" // Print out the newVersion
                // Concatenate "-1" with the new version
                def taggedVersion = "${newVersion}-1"
                echo "Tagged Version: ${taggedVersion}" // Print out the tagged version
                // Tag the latest commit with the new version
                sh """
                    git remote set-url --push origin ${GERRIT_CENTRAL}/${GIT_REPO_PATH}
                    git tag --annotate --message "Tagging latest" --force ${taggedVersion} HEAD
                    git push --force origin ${taggedVersion}
                """
            }
        }
    }
}
def updateChart(path) {
        echo "The version is: ${env.VERSION}"
        withEnv(["UPDATED_CSAR_VERSION=${CSAR_CNIV_PACKAGE_NAME}-${VERSION}.csar"]) {
        docker.image('armdocker.rnd.ericsson.se/proj-axis_test/enm-cn-ci-pipeline-utils').pull()
        docker.image('armdocker.rnd.ericsson.se/proj-axis_test/enm-cn-ci-pipeline-utils').inside {
            sh "python3 /cniv/update_global_yaml_file.py -f ${path} -v ${env.UPDATED_CSAR_VERSION}"
            }
        }
}
def incrementVersion() {
    // Bump the version using docker
    sh '''chmod -R 777 ${WORKSPACE}'''
    sh 'docker run --rm -v $PWD/VERSION_PREFIX:/app/VERSION -w /app armdocker.rnd.ericsson.se/proj-enm/bump minor'
    // Read the new version from the updated file
    def newVersion = readFile('VERSION_PREFIX').trim()
    // Commit and push the changes to the repository
    sh """
        git checkout master
        git add VERSION_PREFIX
        git commit -m "Bump version to ${newVersion}"
        git push --force origin master
    """
    return newVersion
}