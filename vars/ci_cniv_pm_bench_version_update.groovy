#!/usr/bin/env groovy
def call() {
    docker.image('armdocker.rnd.ericsson.se/proj-axis_test/enm-cn-ci-pipeline-utils').pull()
    def CHART_VERSION = sh(script: "cat .bob/var.version", returnStdout: true).trim()
    def fileList = [
        "${env.WORKSPACE}/${CHART_PATH}/values.yaml",
        "${env.WORKSPACE}/${CHART_PATH}/deployment-valuesfile/5kload-values.yaml",
        "${env.WORKSPACE}/${CHART_PATH}/deployment-valuesfile/80kload-values.yaml"
    ]
    fileList.each { filePath ->
        docker.image('armdocker.rnd.ericsson.se/proj-axis_test/enm-cn-ci-pipeline-utils').inside {
            sh "python3 /cniv/update_values_file.py -f ${filePath} -v ${CHART_VERSION} -n eric-enm-pm-bench"
        }
    }
    def commitMessage = "NO JIRA Version updated for eric-enm-pm-bench ${CHART_VERSION}"
    sh "cd ${env.WORKSPACE}/${CHART_PATH} && git add . && git commit -m '${commitMessage}' && git push ${GERRIT_CENTRAL}/${GIT_REPO_PATH} HEAD:${BRANCH}"
}