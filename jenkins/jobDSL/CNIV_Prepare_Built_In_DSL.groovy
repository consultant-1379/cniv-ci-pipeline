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
    pipeline_name = git_repo_path.split('/').last() + '_Prepare'

    pipelineJob(pipeline_name) {
        description('<a href="https://eteamspace.internal.ericsson.com/display/DGBase/CNIV+Pipeline+-+User+Guide">CNIV Pipeline Documentation</a>')

        parameters {
            stringParam('CHART_NAME', '', 'Dependency chart name')
            stringParam('CHART_VERSION', '', 'Dependency chart version')
            textParam('PIPELINE_LOCAL_VARIABLES', 'JAVA_HOME=/app/vbuild/SLED11-x86_64/jdk/1.8.0_172\nPATH=/proj/ciexadm200/tools/apache-maven-3.5.3/bin:${PATH}', 'Multiline string parameter.<br>Define a custom list of environment variables.<br>Global environment variable (Manage Jenkins) will be overwritten.')
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
                    patchsetCreated { // Trigger when a new change or patch set is uploaded.
                        excludeDrafts(false)
                        excludeTrivialRebase(false) // this will ignore any patchset which Gerrit considers a "trivial rebase" from triggering this build.
                        excludeNoCodeChange(false) // this will ignore any patchset which Gerrit considers without any code changes from triggering this build.
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
                        disableStrictForbiddenFileVerification(false)
                    }
                }
            }
        }

        definition {
            cps {
                script(readFileFromWorkspace('jenkins/jenkinsFiles/jenkinsfile-cniv-integration-prepare-built-in.groovy'))
                sandbox(true)
            }
        }
    }
}

// Creates or updates a view that shows items in a simple list format.
listView("CNIV-Prepare") {
    filterBuildQueue()
    filterExecutors()
    jobs {
        regex(/.*Prepare/)
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