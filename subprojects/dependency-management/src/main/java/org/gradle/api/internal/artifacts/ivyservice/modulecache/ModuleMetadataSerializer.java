/*
 * Copyright 2016 the original author or authors.
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
package org.gradle.api.internal.artifacts.ivyservice.modulecache;

import org.gradle.api.artifacts.ModuleVersionSelector;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.internal.artifacts.DefaultModuleVersionSelector;
import org.gradle.api.internal.artifacts.ivyservice.NamespaceId;
import org.gradle.internal.component.external.descriptor.Artifact;
import org.gradle.internal.component.external.descriptor.Configuration;
import org.gradle.internal.component.external.descriptor.DefaultExclude;
import org.gradle.internal.component.external.descriptor.Dependency;
import org.gradle.internal.component.external.descriptor.IvyDependency;
import org.gradle.internal.component.external.descriptor.MavenDependency;
import org.gradle.internal.component.external.descriptor.MavenScope;
import org.gradle.internal.component.external.descriptor.ModuleDescriptorState;
import org.gradle.internal.component.external.descriptor.MutableModuleDescriptorState;
import org.gradle.internal.component.external.model.DefaultModuleComponentIdentifier;
import org.gradle.internal.component.external.model.DefaultMutableIvyModuleResolveMetadata;
import org.gradle.internal.component.external.model.DefaultMutableMavenModuleResolveMetadata;
import org.gradle.internal.component.external.model.IvyModuleResolveMetadata;
import org.gradle.internal.component.external.model.MavenModuleResolveMetadata;
import org.gradle.internal.component.external.model.ModuleComponentResolveMetadata;
import org.gradle.internal.component.external.model.MutableModuleComponentResolveMetadata;
import org.gradle.internal.component.model.DefaultIvyArtifactName;
import org.gradle.internal.component.model.Exclude;
import org.gradle.internal.component.model.IvyArtifactName;
import org.gradle.internal.serialize.Decoder;
import org.gradle.internal.serialize.Encoder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ModuleMetadataSerializer {
    private static final byte TYPE_IVY = 1;
    private static final byte TYPE_MAVEN = 2;

    public MutableModuleComponentResolveMetadata read(Decoder decoder) throws IOException {
        return new Reader(decoder).read();
    }

    public void write(Encoder encoder, ModuleComponentResolveMetadata metadata) throws IOException {
        new Writer(encoder).write(metadata);
    }

    private static class Writer {
        private final Encoder encoder;

        private Writer(Encoder encoder) {
            this.encoder = encoder;
        }

        public void write(ModuleComponentResolveMetadata metadata) throws IOException {
            if (metadata instanceof IvyModuleResolveMetadata) {
                write((IvyModuleResolveMetadata) metadata);
            } else if (metadata instanceof MavenModuleResolveMetadata) {
                write((MavenModuleResolveMetadata) metadata);
            } else {
                throw new IllegalArgumentException("Unexpected metadata type: " + metadata.getClass());
            }
        }

        private void write(MavenModuleResolveMetadata metadata) throws IOException {
            encoder.writeByte(TYPE_MAVEN);
            writeSharedInfo(metadata);
            writeNullableString(metadata.getSnapshotTimestamp());
            writeNullableString(metadata.getPackaging());
            writeBoolean(metadata.isRelocated());
        }

        private void write(IvyModuleResolveMetadata metadata) throws IOException {
            encoder.writeByte(TYPE_IVY);
            writeSharedInfo(metadata);
        }

        private void writeSharedInfo(ModuleComponentResolveMetadata metadata) throws IOException {
            writeId(metadata.getComponentId());
            ModuleDescriptorState md = metadata.getDescriptor();
            writeInfoSection(md);
            writeConfigurations(md.getConfigurations());
            writeArtifacts(md.getArtifacts());
            writeDependencies(md.getDependencies());
            writeExcludeRules(md.getExcludes());
        }

        private void writeId(ModuleComponentIdentifier componentIdentifier) throws IOException {
            writeString(componentIdentifier.getGroup());
            writeString(componentIdentifier.getModule());
            writeString(componentIdentifier.getVersion());
        }

        private void writeInfoSection(ModuleDescriptorState md) throws IOException {
            ModuleComponentIdentifier componentIdentifier = md.getComponentIdentifier();
            writeId(componentIdentifier);
            writeString(md.getStatus());
            writeBoolean(md.isGenerated());

            writeNullableString(md.getDescription());
            writeNullableDate(md.getPublicationDate());
            writeNullableString(md.getBranch());

            writeExtraInfo(md.getExtraInfo());
        }

        private void writeExtraInfo(Map<NamespaceId, String> extraInfo) throws IOException {
            writeCount(extraInfo.size());
            for (Map.Entry<NamespaceId, String> entry : extraInfo.entrySet()) {
                NamespaceId namespaceId = entry.getKey();
                writeString(namespaceId.getNamespace());
                writeString(namespaceId.getName());
                writeString(entry.getValue());
            }
        }

        private void writeConfigurations(Collection<Configuration> configurations) throws IOException {
            writeCount(configurations.size());
            for (Configuration conf : configurations) {
                writeConfiguration(conf);
            }
        }

        private void writeConfiguration(Configuration conf) throws IOException {
            writeString(conf.getName());
            writeBoolean(conf.isTransitive());
            writeBoolean(conf.isVisible());
            writeStringList(conf.getExtendsFrom());
        }

        private void writeArtifacts(List<Artifact> artifacts) throws IOException {
            writeCount(artifacts.size());
            for (Artifact artifact : artifacts) {
                IvyArtifactName artifactName = artifact.getArtifactName();
                writeString(artifactName.getName());
                writeString(artifactName.getType());
                writeNullableString(artifactName.getExtension());
                writeNullableString(artifactName.getClassifier());
                writeStringSet(artifact.getConfigurations());
            }
        }

        private void writeDependencies(List<Dependency> dependencies) throws IOException {
            writeCount(dependencies.size());
            for (Dependency dd : dependencies) {
                writeDependency(dd);
            }
        }

        private void writeDependency(Dependency dep) throws IOException {
            ModuleVersionSelector selector = dep.getRequested();
            writeString(selector.getGroup());
            writeString(selector.getName());
            writeString(selector.getVersion());

            if (dep instanceof IvyDependency) {
                encoder.writeByte(TYPE_IVY);
                writeString(dep.getDynamicConstraintVersion());
                writeBoolean(dep.isForce());
                writeBoolean(dep.isChanging());
                writeBoolean(dep.isTransitive());
            } else if (dep instanceof MavenDependency) {
                MavenDependency mavenDependency = (MavenDependency) dep;
                encoder.writeByte(TYPE_MAVEN);
                encoder.writeSmallInt(mavenDependency.getScope().ordinal());
            } else {
                throw new IllegalStateException("Unexpected dependency type");
            }

            writeDependencyConfigurationMapping(dep);
            writeArtifacts(dep.getDependencyArtifacts());

            writeExcludeRules(dep.getDependencyExcludes());
        }

        private void writeDependencyConfigurationMapping(Dependency dep) throws IOException {
            Map<String, List<String>> confMappings = dep.getConfMappings();
            writeCount(confMappings.size());
            for (Map.Entry<String, List<String>> entry : confMappings.entrySet()) {
                writeString(entry.getKey());
                writeStringList(entry.getValue());
            }
        }

        private void writeExcludeRules(List<Exclude> excludes) throws IOException {
            writeCount(excludes.size());
            for (Exclude exclude : excludes) {
                IvyArtifactName artifact = exclude.getArtifact();
                writeString(exclude.getModuleId().getGroup());
                writeString(exclude.getModuleId().getName());
                writeString(artifact.getName());
                writeString(artifact.getType());
                writeString(artifact.getExtension());
                writeStringArray(exclude.getConfigurations());
                writeString(exclude.getMatcher());
            }
        }

        private void writeCount(int i) throws IOException {
            encoder.writeSmallInt(i);
        }

        private void writeString(String str) throws IOException {
            encoder.writeString(str);
        }

        private void writeNullableString(String str) throws IOException {
            encoder.writeNullableString(str);
        }

        private void writeBoolean(boolean b) throws IOException {
            encoder.writeBoolean(b);
        }

        private void writeNullableDate(Date publicationDate) throws IOException {
            if (publicationDate == null) {
                writeLong(-1);
            } else {
                writeLong(publicationDate.getTime());
            }
        }

        private void writeLong(long l) throws IOException {
            encoder.writeLong(l);
        }

        private void writeStringArray(String[] values) throws IOException {
            writeCount(values.length);
            for (String configuration : values) {
                writeNullableString(configuration);
            }
        }

        private void writeStringList(List<String> values) throws IOException {
            writeCount(values.size());
            for (String configuration : values) {
                writeString(configuration);
            }
        }

        private void writeStringSet(Set<String> values) throws IOException {
            writeCount(values.size());
            for (String configuration : values) {
                writeString(configuration);
            }
        }
    }

    private static class Reader {
        private final Decoder decoder;
        private MutableModuleDescriptorState md;
        private ModuleComponentIdentifier id;

        private Reader(Decoder decoder) {
            this.decoder = decoder;
        }

        public MutableModuleComponentResolveMetadata read() throws IOException {
            byte type = decoder.readByte();
            switch (type) {
                case TYPE_IVY:
                    return readIvy();
                case TYPE_MAVEN:
                    return readMaven();
                default:
                    throw new IllegalArgumentException("Unexpected metadata type found.");
            }
        }

        private void readSharedInfo() throws IOException {
            id = readId();
            readInfoSection();
            readConfigurations();
            readArtifacts();
            readDependencies();
            readAllExcludes();
        }

        private MutableModuleComponentResolveMetadata readMaven() throws IOException {
            readSharedInfo();
            String snapshotTimestamp = readNullableString();
            String packaging = readNullableString();
            boolean relocated = readBoolean();
            DefaultMutableMavenModuleResolveMetadata metadata = new DefaultMutableMavenModuleResolveMetadata(id, md, packaging, relocated);
            metadata.setSnapshotTimestamp(snapshotTimestamp);
            return metadata;
        }

        private MutableModuleComponentResolveMetadata readIvy() throws IOException {
            readSharedInfo();
            return new DefaultMutableIvyModuleResolveMetadata(id, md);
        }

        private void readInfoSection() throws IOException {
            ModuleComponentIdentifier componentIdentifier = readId();
            String status = readString();
            boolean generated = readBoolean();

            md = new MutableModuleDescriptorState(componentIdentifier, status, generated);

            md.setDescription(readNullableString());
            md.setPublicationDate(readNullableDate());
            md.setBranch(readNullableString());

            readExtraInfo();
        }

        private ModuleComponentIdentifier readId() throws IOException {
            return DefaultModuleComponentIdentifier.newId(readString(), readString(), readString());
        }

        private void readExtraInfo() throws IOException {
            int len = readCount();
            for (int i = 0; i < len; i++) {
                NamespaceId namespaceId = new NamespaceId(readString(), readString());
                String value = readString();
                md.getExtraInfo().put(namespaceId, value);
            }
        }

        private void readConfigurations() throws IOException {
            int len = readCount();
            for (int i = 0; i < len; i++) {
                readConfiguration();
            }
        }

        private void readConfiguration() throws IOException {
            String name = readString();
            boolean transitive = readBoolean();
            boolean visible = readBoolean();
            List<String> extendsFrom = readStringList();
            md.addConfiguration(name, transitive, visible, extendsFrom);
        }

        private void readArtifacts() throws IOException {
            int size = readCount();
            for (int i = 0; i < size; i++) {
                IvyArtifactName ivyArtifactName = new DefaultIvyArtifactName(readString(), readString(), readNullableString(), readNullableString());
                md.addArtifact(ivyArtifactName, readStringSet());
            }
        }

        private void readDependencies() throws IOException {
            int len = readCount();
            for (int i = 0; i < len; i++) {
                readDependency();
            }
        }

        private void readDependency() throws IOException {
            ModuleVersionSelector requested = DefaultModuleVersionSelector.newSelector(readString(), readString(), readString());

            byte type = decoder.readByte();
            Dependency dep;
            switch (type) {
                case TYPE_IVY:
                    dep = md.addDependency(new IvyDependency(requested, readString(), readBoolean(), readBoolean(), readBoolean()));
                    break;
                case TYPE_MAVEN:
                    dep = md.addDependency(new MavenDependency(MavenScope.values()[decoder.readSmallInt()], requested));
                    break;
                default:
                    throw new IllegalArgumentException("Unexpected dependency type found.");
            }

            readDependencyConfigurationMapping(dep);
            readDependencyArtifactDescriptors(dep);
            readExcludeRules(dep);
        }

        private void readDependencyConfigurationMapping(Dependency dep) throws IOException {
            int size = readCount();
            for (int i = 0; i < size; i++) {
                String from = readString();
                List<String> to = readStringList();
                dep.addDependencyConfiguration(from, to);
            }
        }

        private void readDependencyArtifactDescriptors(Dependency dep) throws IOException {
            int size = readCount();
            for (int i = 0; i < size; i++) {
                IvyArtifactName ivyArtifactName = new DefaultIvyArtifactName(readString(), readString(), readNullableString(), readNullableString());
                dep.addArtifact(ivyArtifactName, readStringSet());
            }
        }

        private void readExcludeRules(Dependency dep) throws IOException {
            int len = readCount();
            for (int i = 0; i < len; i++) {
                DefaultExclude rule = readExcludeRule();
                dep.addExcludeRule(rule);
            }
        }

        private DefaultExclude readExcludeRule() throws IOException {
            String moduleOrg = readString();
            String moduleName = readString();
            String artifact = readString();
            String type = readString();
            String ext = readString();
            String[] confs = readStringArray();
            String matcher = readString();
            return new DefaultExclude(moduleOrg, moduleName, artifact, type, ext, confs, matcher);
        }

        private void readAllExcludes() throws IOException {
            int len = readCount();
            for (int i = 0; i < len; i++) {
                md.addExclude(readExcludeRule());
            }
        }

        private int readCount() throws IOException {
            return decoder.readSmallInt();
        }

        private int readInt() throws IOException {
            return decoder.readInt();
        }

        private String readString() throws IOException {
            return decoder.readString();
        }

        private String readNullableString() throws IOException {
            return decoder.readNullableString();
        }

        private boolean readBoolean() throws IOException {
            return decoder.readBoolean();
        }

        private Date readNullableDate() throws IOException {
            long value = readLong();
            if (value == -1) {
                return null;
            } else {
                return new Date(value);
            }
        }

        private long readLong() throws IOException {
            return decoder.readLong();
        }

        private String[] readStringArray() throws IOException {
            int size = readCount();
            String[] array = new String[size];
            for (int i = 0; i < size; i++) {
                array[i] = readString();
            }
            return array;
        }

        private List<String> readStringList() throws IOException {
            int size = readCount();
            List<String> list = new ArrayList<String>(size);
            for (int i = 0; i < size; i++) {
                list.add(readString());
            }
            return list;
        }

        private Set<String> readStringSet() throws IOException {
            int size = readCount();
            Set<String> set = new LinkedHashSet<String>(size);
            for (int i = 0; i < size; i++) {
                set.add(readString());
            }
            return set;
        }
    }

}
