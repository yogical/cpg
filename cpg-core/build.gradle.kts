/*
 * Copyright (c) 2021, Fraunhofer AISEC. All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *                    $$$$$$\  $$$$$$$\   $$$$$$\
 *                   $$  __$$\ $$  __$$\ $$  __$$\
 *                   $$ /  \__|$$ |  $$ |$$ /  \__|
 *                   $$ |      $$$$$$$  |$$ |$$$$\
 *                   $$ |      $$  ____/ $$ |\_$$ |
 *                   $$ |  $$\ $$ |      $$ |  $$ |
 *                   \$$$$$   |$$ |      \$$$$$   |
 *                    \______/ \__|       \______/
 *
 */
import com.github.gradle.node.yarn.task.YarnTask

plugins {
    `java-library`
    `java-test-fixtures`
    signing

    id("com.github.node-gradle.node") version "3.2.0"
}

publishing {
    publications {
        named<MavenPublication>("cpg-core") {
            pom {
                artifactId = "cpg-core"
                name.set("Code Property Graph - Core")
                description.set("A simple library to extract a code property graph out of source code. It has support for multiple passes that can extend the analysis after the graph is constructed.")
            }

            suppressPomMetadataWarningsFor("testFixturesApiElements")
            suppressPomMetadataWarningsFor("testFixturesRuntimeElements")
        }
    }
}

tasks.withType<GenerateModuleMetadata> {
    enabled = false
}

tasks.named<Test>("test") {
    useJUnitPlatform {
        if (!project.hasProperty("experimental")) {
            excludeTags("experimental")
        } else {
            systemProperty("java.library.path", project.projectDir.resolve("src/main/golang"))
        }

        if (!project.hasProperty("experimentalTypeScript")) {
            excludeTags("experimentalTypeScript")
        }

        if (!project.hasProperty("experimentalPython")) {
            excludeTags("experimentalPython")
        }
    }
    maxHeapSize = "4048m"
}

node {
    download.set(findProperty("nodeDownload")?.toString()?.toBoolean() ?: false)
    version.set("16.4.2")
}

java {
    withJavadocJar()
    withSourcesJar()
}

val yarnInstall by tasks.registering(YarnTask::class) {
    inputs.file("src/main/nodejs/package.json").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file("src/main/nodejs/yarn.lock").withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir("src/main/nodejs/node_modules")
    outputs.cacheIf { true }

    workingDir.set(file("src/main/nodejs"))
    yarnCommand.set(listOf("install", "--ignore-optional"))
}

val yarnBuild by tasks.registering(YarnTask::class) {
    inputs.file("src/main/nodejs/package.json").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file("src/main/nodejs/yarn.lock").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir("src/main/nodejs/src").withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir("build/resources/main/nodejs")
    outputs.cacheIf { true }

    workingDir.set(file("src/main/nodejs"))
    yarnCommand.set(listOf("bundle"))

    dependsOn(yarnInstall)
}

if (project.hasProperty("experimentalTypeScript")) {
    tasks.processResources {
        dependsOn(yarnBuild)
    }
}

dependencies {
    api("org.apache.commons:commons-lang3:3.12.0")

    api("org.neo4j:neo4j-ogm-core:3.2.28")

    api("org.slf4j:jul-to-slf4j:1.7.36")
    api("org.slf4j:slf4j-api:1.7.36")
    implementation("org.apache.logging.log4j:log4j-slf4j18-impl:2.17.0")

    api("com.github.javaparser:javaparser-symbol-solver-core:3.24.0")
    api("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")

    // Eclipse dependencies
    api("org.eclipse.platform:org.eclipse.core.runtime:3.24.0")
    api("com.ibm.icu:icu4j:70.1")

    // CDT
    api("org.eclipse.cdt:core:7.4.0.202110252256")

    // openCypher
    api("org.opencypher:parser-9.0:9.0.20210312")

    api("commons-io:commons-io:2.11.0")

    implementation(platform("org.jetbrains.kotlin:kotlin-bom"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // jep for python support
    api("black.ninia:jep:4.0.0")

    // JUnit
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testFixturesApi("org.junit.jupiter:junit-jupiter-api:5.8.2")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.8.2")

    testFixturesApi("org.mockito:mockito-core:4.3.1")

    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.8.2")
}
