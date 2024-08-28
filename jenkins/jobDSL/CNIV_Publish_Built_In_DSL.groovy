import java.io.File
import groovy.json.JsonSlurper

project_file_path  = 'integration_charts'
String projects_file = readFileFromWorkspace(project_file_path)

projects_file.eachLine {
    project_details ->
        def env_map = [:] // hashmap to group environment variables contained in projects file

        project_details.tokenize(';').each {
            environment_variable ->
                key = environment_variable.split('=')[0]
                value = environment_variable.split('=')[1]
                env_map[key] = value
        }
        createPipelineJob(env_map)
}

def createPipelineJob(env_map) {
    git_repo_path = env_map['GIT_REPO_PATH']
    branch = env_map['BRANCH']
    pipeline_name = git_repo_path.split('/').last() + '_Publish'

    pipelineJob(pipeline_name) {
        description('<a href="https://eteamspace.internal.ericsson.com/display/DGBase/CNIV+Pipeline+-+User+Guide">CNIV Pipeline Documentation</a>')

        parameters {
            stringParam('CHART_NAME', '', 'Dependency chart name')
            stringParam('CHART_VERSION', '', 'Dependency chart version')
        }

        logRotator {
            numToKeep(20)
        }

        properties {
            disableConcurrentBuilds()
        }

        environmentVariables {
            env_map.each { key, value ->
                env(key, value)
            }
            env('GIT_HOME' , '/proj/ciexadm200/tools/git/2.28.0')
            env('GIT_TOOL' , 'Git 2.28.0')
            env('PATH+GIT' , '${GIT_HOME}/bin')
        }

        triggers {
            gerritTrigger {
                triggerOnEvents {
                    commentAdded { // Trigger when a +2 review comment is left
                        verdictCategory("Code-Review")
                        commentAddedTriggerApprovalValue("+2")
                    }
                }

                // Specify what Gerrit project(s) to trigger a build on.
                gerritProjects {
                    gerritProject {
                        compareType("PLAIN") // The exact repository name in Gerrit, case sensitive equality.
                        pattern(git_repo_path)
                        branches {
                            branch {
                                compareType("PLAIN") // The exact branch name in Gerrit, case sensitive equality.
                                    pattern(branch)
                            }
                        }
                        topics {
                            topic {
                                compareType("REG_EXP") // The Gerrit topic regex
                                    pattern("^((?!inca_cniv)).*\$")
                            }
                        }
                        disableStrictForbiddenFileVerification(false)
                    }
                }
            }
        }

        definition {
            cps {
                script(readFileFromWorkspace('jenkins/jenkinsFiles/jenkinsfile-cniv-integration-publish-built-in.groovy'))
                sandbox(true)
            }
        }
    }
}

// Creates or updates a view that shows items in a simple list format.
listView('CNIV-Publish') {
    filterBuildQueue()
    filterExecutors()
    jobs {
        regex(/.*Publish/)
    }
    columns {
        status()
        weather()
        name()
        lastSuccess()
        lastFailure()
        lastDuration()
        buildButton()
    }
}