package com.google.idea.sdkcompat.general;

import com.intellij.ide.impl.OpenProjectTask;
import com.intellij.openapi.externalSystem.service.project.IdeModifiableModelsProvider;
import com.intellij.openapi.externalSystem.service.project.IdeModifiableModelsProviderImpl;
import com.intellij.openapi.fileChooser.ex.FileLookup;
import com.intellij.openapi.fileChooser.ex.LocalFsFinder;
import com.intellij.ide.impl.ProjectUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.refactoring.rename.RenamePsiElementProcessor;
import com.intellij.util.Restarter;
import com.intellij.util.indexing.diagnostic.dto.JsonDuration;
import com.intellij.util.indexing.diagnostic.dto.JsonFileProviderIndexStatistics;
import java.io.File;
import java.nio.file.Path;
import javax.annotation.Nullable;

/** Provides SDK compatibility shims for base plugin API classes, available to all IDEs. */
public final class BaseSdkCompat {
  private BaseSdkCompat() {}

  /**
   * Creates an {@link IdeModifiableModelsProvider} for performant updates of the project model even
   * when many modifications are involved. {@link IdeModifiableModelsProvider#commit()} must be
   * called for any changes to take effect but call that method only after completing all changes.
   *
   * <p>#api212: New method createModifiableModelsProvider() is only available from 2021.3 on.
   */
  public static IdeModifiableModelsProvider createModifiableModelsProvider(Project project) {
    // Switch to ProjectDataManager#createModifiableModelsProvider in 2021.3 for a public, stable
    // API to create an IdeModifiableModelsProvider.
    return new IdeModifiableModelsProviderImpl(project);
  }

  /** #api213: inline this method */
  @Nullable
  public static String getIdeRestarterPath() {
    File startFile = Restarter.getIdeStarter();
    return startFile == null ? null : startFile.getPath();
  }

  /** #api213: inline into IndexingLogger */
  public static JsonDuration getTotalIndexingTime(
      JsonFileProviderIndexStatistics providerStatisticInput) {
    return providerStatisticInput.getTotalIndexingTime();
  }

  /** #api213: Inline into KytheRenameProcessor. */
  public static RenamePsiElementProcessor[] renamePsiElementProcessorsList() {
    return RenamePsiElementProcessor.EP_NAME.getExtensions();
  }

  /** #api213: Inline into WorkspaceFileTextField . */
  public static LocalFsFinder.VfsFile getVfsFile(VirtualFile file) {
    return new LocalFsFinder.VfsFile(/* unused LocalFsFinder */ null, file);
  }

  /** #api213: Inline into WorkspaceFileTextField. */
  public static FileLookup.LookupFile getIoFile(Path path) {
    return new LocalFsFinder.IoFile(path.toFile());
  }

  /** #api213: Inline into BlazeProjectCreator. */
  public static OpenProjectTask createOpenProjectTask(Project project) {
    return OpenProjectTask.withCreatedProject(project);
  }

  /** #api213 interface is different in 221, inline when 213 support is dropped*/
  public static Project openProject(VirtualFile projectSubdirectory, Project projectToClose, boolean forceOpenInNewFrame) {
    return ProjectUtil.openProject(projectSubdirectory.getPath(), projectToClose, forceOpenInNewFrame);
  }

}
