/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.maven.packaging;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;

import static org.apache.camel.maven.packaging.PackageHelper.after;
import static org.apache.camel.maven.packaging.PackageHelper.loadText;
import static org.apache.camel.maven.packaging.PackageHelper.parseAsMap;

/**
 * Analyses the Camel plugins in a project and generates extra descriptor information for easier auto-discovery in Camel.
 *
 * @goal generate-languages-list
 * @execute phase="generate-resources"
 * @execute phase="process-classes"
 */
public class PackageLanguageMojo extends AbstractMojo {

    /**
     * The maven project.
     *
     * @parameter property="project"
     * @required
     * @readonly
     */
    protected MavenProject project;

    /**
     * The output directory for generated languages file
     *
     * @parameter default-value="${project.build.directory}/generated/camel/languages"
     */
    protected File outDir;

    /**
     * The output directory for generated languages file
     *
     * @parameter default-value="${project.build.directory}/classes"
     */
    protected File schemaOutDir;

    /**
     * Maven ProjectHelper.
     *
     * @component
     * @readonly
     */
    private MavenProjectHelper projectHelper;

    /**
     * Execute goal.
     *
     * @throws org.apache.maven.plugin.MojoExecutionException execution of the main class or one of the
     *                                                        threads it generated failed.
     * @throws org.apache.maven.plugin.MojoFailureException   something bad happened...
     */
    public void execute() throws MojoExecutionException, MojoFailureException {
        File camelMetaDir = new File(outDir, "META-INF/services/org/apache/camel/");

        Map<String, String> javaTypes = new HashMap<String, String>();

        StringBuilder buffer = new StringBuilder();
        int count = 0;
        for (Resource r : project.getBuild().getResources()) {
            File f = new File(r.getDirectory());
            if (!f.exists()) {
                f = new File(project.getBasedir(), r.getDirectory());
            }
            f = new File(f, "META-INF/services/org/apache/camel/language");

            if (f.exists() && f.isDirectory()) {
                File[] files = f.listFiles();
                if (files != null) {
                    for (File file : files) {
                        // skip directories as there may be a sub .resolver directory such as in camel-script
                        if (file.isDirectory()) {
                            continue;
                        }
                        String name = file.getName();
                        if (name.charAt(0) != '.') {
                            count++;
                            if (buffer.length() > 0) {
                                buffer.append(" ");
                            }
                            buffer.append(name);
                        }

                        // find out the javaType for each data format
                        try {
                            String text = loadText(new FileInputStream(file));
                            Map<String, String> map = parseAsMap(text);
                            String javaType = map.get("class");
                            if (javaType != null) {
                                javaTypes.put(name, javaType);
                            }
                        } catch (IOException e) {
                            throw new MojoExecutionException("Failed to read file " + file + ". Reason: " + e, e);
                        }
                    }
                }
            }
        }

        // find camel-core and grab the data format model from there, and enrich this model with information from this artifact
        // and create json schema model file for this data format
        try {
            if (count > 0) {
                Artifact camelCore = findCamelCoreArtifact(project);
                if (camelCore != null) {
                    File core = camelCore.getFile();
                    if (core != null) {
                        URL url = new URL("file", null, core.getAbsolutePath());
                        URLClassLoader loader = new URLClassLoader(new URL[]{url});
                        for (Map.Entry<String, String> entry : javaTypes.entrySet()) {
                            String name = entry.getKey();
                            String javaType = entry.getValue();
                            String modelName = asModelName(name);

                            InputStream is = loader.getResourceAsStream("org/apache/camel/model/language/" + modelName + ".json");
                            if (is == null) {
                                // use file input stream if we build camel-core itself, and thus do not have a JAR which can be loaded by URLClassLoader
                                is = new FileInputStream(new File(core, "org/apache/camel/model/language/" + modelName + ".json"));
                            }
                            String json = loadText(is);
                            if (json != null) {
                                LanguageModel languageModel = new LanguageModel();
                                languageModel.setName(name);
                                languageModel.setModelName(modelName);
                                languageModel.setLabel("");
                                languageModel.setDescription(project.getDescription());
                                languageModel.setJavaType(javaType);
                                languageModel.setGroupId(project.getGroupId());
                                languageModel.setArtifactId(project.getArtifactId());
                                languageModel.setVersion(project.getVersion());

                                List<Map<String, String>> rows = JSonSchemaHelper.parseJsonSchema("model", json, false);
                                for (Map<String, String> row : rows) {
                                    if (row.containsKey("label")) {
                                        languageModel.setLabel(row.get("label"));
                                    }
                                    if (row.containsKey("javaType")) {
                                        languageModel.setModelJavaType(row.get("javaType"));
                                    }
                                    // override description for camel-core, as otherwise its too generic
                                    if ("camel-core".equals(project.getArtifactId())) {
                                        if (row.containsKey("description")) {
                                            languageModel.setLabel(row.get("description"));
                                        }
                                    }
                                }
                                getLog().debug("Model " + languageModel);

                                // build json schema for the data format
                                String properties = after(json, "  \"properties\": {");
                                String schema = createParameterJsonSchema(languageModel, properties);
                                getLog().debug("JSon schema\n" + schema);

                                // write this to the directory
                                File dir = new File(schemaOutDir, schemaSubDirectory(languageModel.getJavaType()));
                                dir.mkdirs();

                                File out = new File(dir, name + ".json");
                                FileOutputStream fos = new FileOutputStream(out, false);
                                fos.write(schema.getBytes());
                                fos.close();

                                getLog().info("Generated " + out + " containing JSon schema for " + name + " language");
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new MojoExecutionException("Error loading language model from camel-core. Reason: " + e, e);
        }

        if (count > 0) {
            Properties properties = new Properties();
            String names = buffer.toString();
            properties.put("languages", names);
            properties.put("groupId", project.getGroupId());
            properties.put("artifactId", project.getArtifactId());
            properties.put("version", project.getVersion());
            properties.put("projectName", project.getName());
            properties.put("projectDescription", project.getDescription());

            camelMetaDir.mkdirs();
            File outFile = new File(camelMetaDir, "language.properties");
            try {
                properties.store(new FileWriter(outFile), "Generated by camel-package-maven-plugin");
                getLog().info("Generated " + outFile + " containing " + count + " Camel " + (count > 1 ? "languages: " : "language: ") + names);

                if (projectHelper != null) {
                    List<String> includes = new ArrayList<String>();
                    includes.add("**/language.properties");
                    projectHelper.addResource(this.project, outDir.getPath(), includes, new ArrayList<String>());
                    projectHelper.attachArtifact(this.project, "properties", "camelLanguage", outFile);
                }
            } catch (IOException e) {
                throw new MojoExecutionException("Failed to write properties to " + outFile + ". Reason: " + e, e);
            }
        } else {
            getLog().debug("No META-INF/services/org/apache/camel/language directory found. Are you sure you have created a Camel language?");
        }
    }

    private String asModelName(String name) {
        // special for some languages
        if ("bean".equals(name)) {
            return "method";
        } else if ("file".equals(name)) {
            return "simple";
        }
        return name;
    }

    private Artifact findCamelCoreArtifact(MavenProject project) {
        // maybe this project is camel-core itself
        Artifact artifact = project.getArtifact();
        if (artifact.getGroupId().equals("org.apache.camel") && artifact.getArtifactId().equals("camel-core")) {
            return artifact;
        }

        // or its a component which has a dependency to camel-core
        Iterator it = project.getDependencyArtifacts().iterator();
        while (it.hasNext()) {
            artifact = (Artifact) it.next();
            if (artifact.getGroupId().equals("org.apache.camel") && artifact.getArtifactId().equals("camel-core")) {
                return artifact;
            }
        }
        return null;
    }

    private String schemaSubDirectory(String javaType) {
        int idx = javaType.lastIndexOf('.');
        String pckName = javaType.substring(0, idx);
        return pckName.replace('.', '/');
    }

    private String createParameterJsonSchema(LanguageModel languageModel, String schema) {
        StringBuilder buffer = new StringBuilder("{");
        // component model
        buffer.append("\n \"language\": {");
        buffer.append("\n    \"name\": \"").append(languageModel.getName()).append("\",");
        buffer.append("\n    \"modelName\": \"").append(languageModel.getModelName()).append("\",");
        buffer.append("\n    \"description\": \"").append(languageModel.getDescription()).append("\",");
        buffer.append("\n    \"label\": \"").append(languageModel.getLabel()).append("\",");
        buffer.append("\n    \"javaType\": \"").append(languageModel.getJavaType()).append("\",");
        if (languageModel.getModelJavaType() != null) {
            buffer.append("\n    \"modelJavaType\": \"").append(languageModel.getModelJavaType()).append("\",");
        }
        buffer.append("\n    \"groupId\": \"").append(languageModel.getGroupId()).append("\",");
        buffer.append("\n    \"artifactId\": \"").append(languageModel.getArtifactId()).append("\",");
        buffer.append("\n    \"version\": \"").append(languageModel.getVersion()).append("\"");
        buffer.append("\n  },");

        buffer.append("\n  \"properties\": {");
        buffer.append(schema);
        return buffer.toString();
    }

    private class LanguageModel {
        private String name;
        private String modelName;
        private String description;
        private String label;
        private String javaType;
        private String modelJavaType;
        private String groupId;
        private String artifactId;
        private String version;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getModelName() {
            return modelName;
        }

        public void setModelName(String modelName) {
            this.modelName = modelName;
        }

        public String getModelJavaType() {
            return modelJavaType;
        }

        public void setModelJavaType(String modelJavaType) {
            this.modelJavaType = modelJavaType;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public String getJavaType() {
            return javaType;
        }

        public void setJavaType(String javaType) {
            this.javaType = javaType;
        }

        public String getGroupId() {
            return groupId;
        }

        public void setGroupId(String groupId) {
            this.groupId = groupId;
        }

        public String getArtifactId() {
            return artifactId;
        }

        public void setArtifactId(String artifactId) {
            this.artifactId = artifactId;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        @Override
        public String toString() {
            return "LanguageModel["
                    + "name='" + name + '\''
                    + ", modelName='" + modelName + '\''
                    + ", description='" + description + '\''
                    + ", label='" + label + '\''
                    + ", javaType='" + javaType + '\''
                    + ", modelJavaType='" + modelJavaType + '\''
                    + ", groupId='" + groupId + '\''
                    + ", artifactId='" + artifactId + '\''
                    + ", version='" + version + '\''
                    + ']';
        }
    }

}