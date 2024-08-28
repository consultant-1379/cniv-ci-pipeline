import groovy.json.JsonSlurper
pipeline {
    agent {
        node {
            label 'Cloud-Native'
        }
    }
    parameters {
        string(name: 'CHART_NAME', defaultValue: '', description: 'Specify the chart name: eric-cniv-common-helmchart-library or eric-cniv-init-bench')
        string(name: 'CHART_VERSION', defaultValue: '', description: 'Specify the version to update')
    }
    stages {
        stage('Validate Parameters') {
            steps {
                script {
                    def allowedChartNames = ['eric-cniv-common-helmchart-library', 'eric-cniv-init-bench']
                    if (CHART_NAME.isEmpty() || !(allowedChartNames.contains(CHART_NAME))) {
                        echo "Error: Invalid CHART_NAME. Allowed values are: ${allowedChartNames.join(', ')}"
                        currentBuild.result = 'FAILURE'
                        error("Build failed due to invalid CHART_NAME.")
                    }
                    if (CHART_VERSION.isEmpty()) {
                        echo "Error: CHART_VERSION is empty. Please provide a value."
                        currentBuild.result = 'FAILURE'
                        error("Build failed due to empty CHART_VERSION.")
                    }
                }
            }
        }
        stage('Clean up') {
            steps {
                deleteDir()
            }
        }
        stage('Checkout') {
            steps {
                git branch: 'master',
                    url: '${GERRIT_MIRROR}/OSS/ENM-Parent/SQ-Gate/com.ericsson.oss.containerisation/cniv-ci-pipeline'
            }
        }
        stage('Inject Credential Files') {
            steps {
                withCredentials([file(credentialsId: 'lciadm100-docker-auth', variable: 'dockerConfig')]) {
                    sh "install -m 600 ${dockerConfig} ${HOME}/.docker/config.json"
                }
            }
        }
        stage('Checkout and Update') {
            steps {
                script {
                    def jsonFilePath = "${env.WORKSPACE}/jenkins/cnivFiles/cniv_repos.json"
                    if (fileExists(jsonFilePath)) {
                        def jsonArray = readJSON file: jsonFilePath
                        echo "Reading Json file"
                        for (jsonObject in jsonArray["repos"]) {
                            if (!jsonObject.containsKey("yamlFiles") || !jsonObject["yamlFiles"]) {
                                echo "No yamlFiles mentioned in JSON for repository ${gitRepo}. Skipping update."
                                continue
                            }
                            def repoName = jsonObject['gitRepo'].tokenize('/').last()
                            def chartPath = jsonObject['chartPath']
                            def gitRepo = jsonObject['gitRepo']
                            echo "Checking out repository: ${gitRepo} under ${repoName}"
                            def branch = jsonObject['branch']
                            if (!branch || branch.trim() == '') {
                                echo "Branch not provided or empty for repository ${gitRepo}. Failing pipeline."
                                currentBuild.result = 'FAILURE'
                                error("Branch not provided or empty for repository ${gitRepo}")
                            }
                            dir("${env.WORKSPACE}/${repoName}") {
                                git branch: branch, url: "${GERRIT_MIRROR}/${gitRepo}"
                                sh "scp -p -P 29418 ${GERRIT_CENTRAL_BASIC}:hooks/commit-msg .git/hooks/"
                                echo "Pulling Docker Image for ${repoName}"
                                docker.image('armdocker.rnd.ericsson.se/proj-axis_test/enm-cn-ci-pipeline-utils').pull()
                                withEnv(["CHART_NAME=${CHART_NAME}", "CHART_VERSION=${CHART_VERSION}", "REPO_NAME=${repoName}", "CHART_PATH=${chartPath}"]) {
                                    def chartFilePath = "${env.WORKSPACE}/${repoName}/${chartPath}/Chart.yaml"
                                    def valuesFilePath = "${env.WORKSPACE}/${repoName}/${chartPath}/values.yaml"
                                    def file5kloadValuesPath = "${env.WORKSPACE}/${repoName}/${chartPath}/deployment-valuesfile/5kload-values.yaml"
                                    def file80kloadValuesPath = "${env.WORKSPACE}/${repoName}/${chartPath}/deployment-valuesfile/80kload-values.yaml"
                                    if (CHART_NAME == 'eric-cniv-common-helmchart-library' && "Chart.yaml" in jsonObject["yamlFiles"]) {
                                        updateChart(chartFilePath)
                                        commitAndPush(repoName, chartPath, branch, gitRepo)
                                    }
                                    else if (CHART_NAME == 'eric-cniv-init-bench' && "values.yaml" in jsonObject["yamlFiles"]) {
                                             updateValues(valuesFilePath, CHART_NAME)
                                         if (gitRepo.contains("eric-enm-pm-benchmark")) {
                                             updateValues(file5kloadValuesPath, CHART_NAME)
                                             updateValues(file80kloadValuesPath, CHART_NAME)
                                         }
                                        commitAndPush(repoName, chartPath, branch, gitRepo)
                                    }
                                }
                            }
                        }
                    } else {
                        echo "ERROR: File not found - ${jsonFilePath}"
                        currentBuild.result = 'FAILURE'
                        error("Build failed due to file not found: ${jsonFilePath}")
                    }
                }
            }
        }
    }
    post {
        failure {
            mail to: 'PDLAZELLES@pdl.internal.ericsson.com',
            from: "enmadm100@lmera.ericsson.se",
            subject: "Failed Pipeline: ${currentBuild.fullDisplayName}",
            body: "Failure on ${env.BUILD_URL}"
        }
    }
}
def updateChart(chartFilePath) {
    docker.image('armdocker.rnd.ericsson.se/proj-axis_test/enm-cn-ci-pipeline-utils').inside {
        sh "python3 /cniv/update_chart_file.py -f ${chartFilePath} -v ${CHART_VERSION}"
    }
}
def updateValues(valuesFilePath, CHART_NAME) {
    docker.image('armdocker.rnd.ericsson.se/proj-axis_test/enm-cn-ci-pipeline-utils').inside {
        sh "python3 /cniv/update_values_file.py -f ${valuesFilePath} -v ${CHART_VERSION} -n ${CHART_NAME}"
    }
}
def commitAndPush(repoName, chartPath, branch, gitRepo) {
    def commitMessage = "NO JIRA Version updated for ${CHART_NAME} ${CHART_VERSION}"
    sh "cd ${env.WORKSPACE}/${repoName}/${chartPath} && git add . && git commit -m '${commitMessage}' && git push ${GERRIT_CENTRAL}/${gitRepo} HEAD:refs/for/${branch}"
}