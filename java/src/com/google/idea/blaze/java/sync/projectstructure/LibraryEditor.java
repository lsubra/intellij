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
package com.google.idea.blaze.java.sync.projectstructure;

import com.google.common.collect.Sets;
import com.google.idea.blaze.base.model.BlazeProjectData;
import com.google.idea.blaze.base.scope.BlazeContext;
import com.google.idea.blaze.base.scope.output.PrintOutput;
import com.google.idea.blaze.base.sync.workspace.ArtifactLocationDecoder;
import com.google.idea.blaze.java.sync.BlazeJavaSyncAugmenter;
import com.google.idea.blaze.java.sync.model.BlazeLibrary;
import com.google.idea.blaze.java.sync.model.LibraryKey;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModifiableRootModel;
import com.intellij.openapi.roots.OrderRootType;
import com.intellij.openapi.roots.impl.libraries.ProjectLibraryTable;
import com.intellij.openapi.roots.libraries.Library;
import com.intellij.openapi.roots.libraries.LibraryTable;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

/** Edits IntelliJ libraries */
public class LibraryEditor {
  private static final Logger LOG = Logger.getInstance(LibraryEditor.class);

  public static void updateProjectLibraries(
      Project project,
      BlazeContext context,
      BlazeProjectData blazeProjectData,
      Collection<BlazeLibrary> libraries) {
    Set<LibraryKey> intelliJLibraryState = Sets.newHashSet();
    for (Library library : ProjectLibraryTable.getInstance(project).getLibraries()) {
      String name = library.getName();
      if (name != null) {
        intelliJLibraryState.add(LibraryKey.fromIntelliJLibraryName(name));
      }
    }
    context.output(PrintOutput.log(String.format("Workspace has %d libraries", libraries.size())));

    Set<String> externallyAddedLibraries = Sets.newHashSet();
    for (BlazeJavaSyncAugmenter augmenter :
        BlazeJavaSyncAugmenter.getActiveSyncAgumenters(
            blazeProjectData.workspaceLanguageSettings)) {
      externallyAddedLibraries.addAll(augmenter.getExternallyAddedLibraries(blazeProjectData));
    }

    LibraryTable libraryTable = ProjectLibraryTable.getInstance(project);
    LibraryTable.ModifiableModel libraryTableModel = libraryTable.getModifiableModel();
    try {
      for (BlazeLibrary library : libraries) {
        updateLibrary(
            project,
            blazeProjectData.artifactLocationDecoder,
            libraryTable,
            libraryTableModel,
            library);
      }

      // Garbage collect unused libraries
      Set<LibraryKey> newLibraryKeys =
          libraries.stream().map((blazeLibrary) -> blazeLibrary.key).collect(Collectors.toSet());
      for (LibraryKey libraryKey : intelliJLibraryState) {
        String libraryIntellijName = libraryKey.getIntelliJLibraryName();
        if (!newLibraryKeys.contains(libraryKey)
            && !externallyAddedLibraries.contains(libraryIntellijName)) {
          Library library = libraryTable.getLibraryByName(libraryIntellijName);
          if (library != null) {
            libraryTableModel.removeLibrary(library);
          }
        }
      }
    } finally {
      libraryTableModel.commit();
    }
  }

  public static void updateLibrary(
      Project project,
      ArtifactLocationDecoder artifactLocationDecoder,
      LibraryTable libraryTable,
      LibraryTable.ModifiableModel libraryTableModel,
      BlazeLibrary blazeLibrary) {
    String libraryName = blazeLibrary.key.getIntelliJLibraryName();

    Library library = libraryTable.getLibraryByName(libraryName);
    boolean libraryExists = library != null;
    if (!libraryExists) {
      library = libraryTableModel.createLibrary(libraryName);
    }
    Library.ModifiableModel libraryModel = library.getModifiableModel();
    if (libraryExists) {
      for (String url : libraryModel.getUrls(OrderRootType.CLASSES)) {
        libraryModel.removeRoot(url, OrderRootType.CLASSES);
      }
      for (String url : libraryModel.getUrls(OrderRootType.SOURCES)) {
        libraryModel.removeRoot(url, OrderRootType.SOURCES);
      }
    }
    try {
      blazeLibrary.modifyLibraryModel(project, artifactLocationDecoder, libraryModel);
    } finally {
      libraryModel.commit();
    }
  }

  public static void configureDependencies(
      ModifiableRootModel modifiableRootModel, Collection<BlazeLibrary> libraries) {
    for (BlazeLibrary library : libraries) {
      updateLibraryDependency(modifiableRootModel, library.key);
    }
  }

  public static void configureDependencies(
      ModifiableRootModel modifiableRootModel, Collection<BlazeLibrary> libraries,
      Project project, String moduleName) {
    // Add all modules named external to the list before libraries so that source code is
    // resolved via them.
    for (Module module : ModuleManager.getInstance(project).getModules()) {
      if (module.getName().startsWith("external-")) {
        modifiableRootModel
            .addModuleOrderEntry(ModuleManager.getInstance(project).getModifiableModel()
                .findModuleByName(moduleName));
      }
    }
    for (BlazeLibrary library : libraries) {
      updateLibraryDependency(modifiableRootModel, library.key);
    }
  }

  private static void updateLibraryDependency(ModifiableRootModel model, LibraryKey libraryKey) {
    LibraryTable libraryTable = ProjectLibraryTable.getInstance(model.getProject());
    Library library = libraryTable.getLibraryByName(libraryKey.getIntelliJLibraryName());
    if (library == null) {
      LOG.error(
          "Library missing: "
              + libraryKey.getIntelliJLibraryName()
              + ". Please resync project to resolve.");
      return;
    }
    model.addLibraryEntry(library);
  }
}
