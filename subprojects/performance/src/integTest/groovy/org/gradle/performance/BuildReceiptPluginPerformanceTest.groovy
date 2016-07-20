/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.performance

import groovy.json.JsonSlurper
import org.gradle.performance.fixture.BuildExperimentRunner
import org.gradle.performance.fixture.BuildReceiptPerformanceTestRunner
import org.gradle.performance.fixture.CrossBuildPerformanceTestRunner
import org.gradle.performance.fixture.GradleSessionProvider
import org.gradle.performance.results.BuildReceiptsResultsStore
import org.gradle.test.fixtures.file.TestNameTestDirectoryProvider
import org.junit.Rule
import org.junit.experimental.categories.Category
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

import static org.gradle.performance.measure.DataAmount.mbytes
import static org.gradle.performance.measure.Duration.millis

@Category(org.gradle.performance.categories.BuildReceiptPluginPerformanceTest)
class BuildReceiptPluginPerformanceTest extends Specification {

    @Rule
    TestNameTestDirectoryProvider tmpDir = new TestNameTestDirectoryProvider()

    @AutoCleanup
    @Shared
    def resultStore = new BuildReceiptsResultsStore()

    private static final String WITH_PLUGIN_LABEL = "with plugin"
    private static final String WITHOUT_PLUGIN_LABEL = "without plugin"

    CrossBuildPerformanceTestRunner runner

    void setup() {
        def incomingDir = System.getProperty('incomingArtifactDir')
        assert incomingDir: "'incomingArtifactDir' system property is not set"
        def versionJsonFile = new File(incomingDir, "version.json")
        assert versionJsonFile.exists()

        def versionJsonData = new JsonSlurper().parse(versionJsonFile) as Map<String, ?>
        assert versionJsonData.commitId
        def pluginCommitId = versionJsonData.commitId as String

        runner = new BuildReceiptPerformanceTestRunner(new BuildExperimentRunner(new GradleSessionProvider(tmpDir)), resultStore, pluginCommitId)
    }

    def "build receipt plugin comparison"() {
        given:
        def sourceProject = "largeJavaProjectWithBuildReceiptPlugin"
        def tasks = ['clean', 'build']
        def gradleOpts = ['-Xms4g', '-Xmx4g', '-XX:MaxPermSize=512m']

        runner.testGroup = "build receipt plugin"
        runner.testId = "large java project with and without build receipt"

        runner.baseline {
            projectName(sourceProject).displayName(WITHOUT_PLUGIN_LABEL).invocation {
                tasksToRun(*tasks)
                invocation {
                    gradleOptions = gradleOpts
                    expectFailure()
                }
            }
        }

        runner.buildSpec {
            projectName(sourceProject).displayName(WITH_PLUGIN_LABEL).invocation {
                args("-Dreceipt", "-Dreceipt.dump")
                tasksToRun(*tasks)
                invocation {
                    gradleOptions = gradleOpts
                    expectFailure()
                }
            }
        }

        when:
        def results = runner.run()

        then:
        def (with, without) = [results.buildResult(WITH_PLUGIN_LABEL), results.buildResult(WITHOUT_PLUGIN_LABEL)]

        // cannot be more than one second slower
        with.totalTime.average - without.totalTime.average < millis(1500)

        // cannot use 40MB more “memory”
        with.totalMemoryUsed.average - without.totalMemoryUsed.average < mbytes(40)
    }

}