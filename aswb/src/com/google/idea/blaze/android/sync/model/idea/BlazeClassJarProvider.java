/*
 * Copyright 2016 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.idea.blaze.android.sync.model.idea;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.android.tools.idea.model.ClassJarProvider;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.idea.blaze.android.libraries.RenderJarCache;
import com.google.idea.blaze.android.sync.model.AndroidResourceModuleRegistry;
import com.google.idea.blaze.android.targetmaps.TargetToBinaryMap;
import com.google.idea.blaze.base.build.BlazeBuildService;
import com.google.idea.blaze.base.command.buildresult.OutputArtifactResolver;
import com.google.idea.blaze.base.ideinfo.ArtifactLocation;
import com.google.idea.blaze.base.ideinfo.JavaIdeInfo;
import com.google.idea.blaze.base.ideinfo.LibraryArtifact;
import com.google.idea.blaze.base.ideinfo.TargetIdeInfo;
import com.google.idea.blaze.base.ideinfo.TargetKey;
import com.google.idea.blaze.base.ideinfo.TargetMap;
import com.google.idea.blaze.base.model.BlazeProjectData;
import com.google.idea.blaze.base.qsync.QuerySync;
import com.google.idea.blaze.base.sync.data.BlazeProjectDataManager;
import com.google.idea.blaze.base.sync.data.BlazeDataStorage;
import com.google.idea.blaze.base.sync.workspace.ArtifactLocationDecoder;
import com.google.idea.blaze.base.targetmaps.TransitiveDependencyMap;
import com.google.idea.common.experiments.BoolExperiment;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.JarFileSystem;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.GlobalSearchScope;
import java.io.File;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** Collects class jars from the user's build. */
public class BlazeClassJarProvider implements ClassJarProvider {
  private static final BoolExperiment useRenderJarForExternalLibraries =
      new BoolExperiment("aswb.classjars.renderjar.as.libraries", true);
  private final Project project;

  public BlazeClassJarProvider(final Project project) {
    this.project = project;
  }

  @Override
  public List<File> getModuleExternalLibraries(Module module) {

    // TODO(b/266726517): Query sync does not support render jars.
    if (QuerySync.isEnabled()) {
      return ImmutableList.of();
    }

    BlazeProjectData blazeProjectData =
        BlazeProjectDataManager.getInstance(project).getBlazeProjectData();

    if (blazeProjectData == null) {
      return ImmutableList.of();
    }

    TargetMap targetMap = blazeProjectData.getTargetMap();
    ArtifactLocationDecoder decoder = blazeProjectData.getArtifactLocationDecoder();
    boolean isWorkspaceModule = BlazeDataStorage.WORKSPACE_MODULE_NAME.equals(module.getName());

    if (isWorkspaceModule) {
      return getAllExternalLibraries(targetMap, decoder);
    }

    if (useRenderJarForExternalLibraries.getValue()) {
      ImmutableList<File> renderJarLibraries = TargetToBinaryMap.getInstance(project).getSourceBinaryTargets().stream()
              .filter(targetMap::contains)
              .map(
                      (binaryTarget) ->
                              RenderJarCache.getInstance(project)
                                      .getCachedJarForBinaryTarget(decoder, targetMap.get(binaryTarget)))
              .filter(Objects::nonNull)
              .collect(toImmutableList());
      if (!renderJarLibraries.isEmpty()) {
        return renderJarLibraries;
      }
    }

    AndroidResourceModuleRegistry registry = AndroidResourceModuleRegistry.getInstance(project);
    TargetIdeInfo target = targetMap.get(registry.getTargetKey(module));
    if (target == null) {
      return ImmutableList.of();
    }

    return targetMap.targets().stream()
      .map(x -> x.getJavaIdeInfo())
      .filter(Objects::nonNull)
      .flatMap(x -> x.getJars().stream())
      .map(LibraryArtifact::getClassJar).filter(Objects::nonNull)
      .map(x -> OutputArtifactResolver.resolve(project, decoder, x))
      .collect(Collectors.toList());

  }

  // @Override #api212
  public boolean isClassFileOutOfDate(Module module, String fqcn, VirtualFile classFile) {
    return testIsClassFileOutOfDate(project, fqcn, classFile);
  }

  public static boolean testIsClassFileOutOfDate(
      Project project, String fqcn, VirtualFile classFile) {
    VirtualFile sourceFile =
        ApplicationManager.getApplication()
            .runReadAction(
                (Computable<VirtualFile>)
                    () -> {
                      PsiClass psiClass =
                          JavaPsiFacade.getInstance(project)
                              .findClass(fqcn, GlobalSearchScope.projectScope(project));
                      if (psiClass == null) {
                        return null;
                      }
                      PsiFile psiFile = psiClass.getContainingFile();
                      if (psiFile == null) {
                        return null;
                      }
                      return psiFile.getVirtualFile();
                    });
    if (sourceFile == null) {
      return false;
    }

    // Edited but not yet saved?
    if (FileDocumentManager.getInstance().isFileModified(sourceFile)) {
      return true;
    }

    long sourceTimeStamp = sourceFile.getTimeStamp();
    long buildTimeStamp = classFile.getTimeStamp();

    if (classFile.getFileSystem() instanceof JarFileSystem) {
      JarFileSystem jarFileSystem = (JarFileSystem) classFile.getFileSystem();
      VirtualFile jarFile = jarFileSystem.getVirtualFileForJar(classFile);
      if (jarFile != null) {
        if (jarFile.getFileSystem() instanceof LocalFileSystem) {
          // The virtual file timestamp could be stale since we don't watch this file.
          buildTimeStamp = VfsUtilCore.virtualToIoFile(jarFile).lastModified();
        } else {
          buildTimeStamp = jarFile.getTimeStamp();
        }
      }
    }

    if (sourceTimeStamp > buildTimeStamp) {
      // It's possible that the source file's timestamp has been updated, but the content remains
      // same. In this case, blaze will not try to rebuild the jar, we have to also check whether
      // the user recently clicked the build button. So they can at least manually get rid of the
      // error.
      Long projectBuildTimeStamp = BlazeBuildService.getLastBuildTimeStamp(project);
      return projectBuildTimeStamp == null || sourceTimeStamp > projectBuildTimeStamp;
    }

    return false;
  }

  List<File> getAllExternalLibraries(TargetMap targetMap, ArtifactLocationDecoder decoder) {
    return targetMap.targets().stream()
            .map(x -> x.getJavaIdeInfo())
            .filter(Objects::nonNull)
            .flatMap(x -> x.getJars().stream())
            .map(LibraryArtifact::getClassJar).filter(Objects::nonNull)
            .map(x -> OutputArtifactResolver.resolve(project, decoder, x))
            .collect(Collectors.toList());
  }
}
