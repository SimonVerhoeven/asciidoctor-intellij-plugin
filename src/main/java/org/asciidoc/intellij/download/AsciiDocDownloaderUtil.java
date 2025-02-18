// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.asciidoc.intellij.download;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationListener;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.progress.impl.BackgroundableProcessIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfoRt;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.download.DownloadableFileDescription;
import com.intellij.util.download.DownloadableFileService;
import com.intellij.util.download.FileDownloader;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.asciidoc.intellij.AsciiDocBundle;
import org.asciidoc.intellij.settings.AsciiDocApplicationSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Helps downloading additional resources from the Internet.
 * It automates the download and provides callbacks once the files are present.
 * It displays notifications when the download fails and allows the user to pick a file that they downloaded
 * manually and moves it to the target folder.
 */
public class AsciiDocDownloaderUtil {

  private static final Logger LOG = Logger.getInstance(AsciiDocDownloaderUtil.class);

  // when updating the version, also update the sha256 hash!
  // https://repo1.maven.org/maven2/org/asciidoctor/asciidoctorj-pdf
  public static final String ASCIIDOCTORJ_PDF_VERSION = "2.3.7";
  private static final String ASCIIDOCTORJ_PDF_HASH = "9c3d8e8f9ce10ecef50ff7b2456562f4e43abbbded2a1a36bdfab8c7096be1c7";

  // when updating the version, also update the sha256 hash!
  // https://repo1.maven.org/maven2/org/asciidoctor/asciidoctorj-diagram
  public static final String ASCIIDOCTORJ_DIAGRAM_VERSION = "2.2.9";
  private static final String ASCIIDOCTORJ_DIAGRAM_HASH = "2b64eaa7533b5b7adcd87904422ca1a7e51e214abe131392e6b26bc1c0021507";

  // https://repo1.maven.org/maven2/org/asciidoctor/asciidoctorj-diagram-plantuml/
  public static final String ASCIIDOCTORJ_DIAGRAM_PLANTUML_VERSION = "1.2023.5";
  private static final String ASCIIDOCTORJ_DIAGRAM_PLANTUML_HASH = "321341cb3fe6e05043b3ea72653f28a5e307d2f274a9fbe196cc76552c58a4ec";

  // https://repo1.maven.org/maven2/org/asciidoctor/asciidoctorj-diagram-ditaamini/
  public static final String ASCIIDOCTORJ_DIAGRAM_DITAAMINI_VERSION = "1.0.3";
  private static final String ASCIIDOCTORJ_DIAGRAM_DITAAMINI_HASH = "5e1dde15a88bf4ca2be60663ae12fcf8c215321e8eeca4e7947fb22732556cc9";


  // https://github.com/jgm/pandoc/releases/
  public static final String PANDOC_VERSION = "3.1.2";
  private static final String PANDOC_WINDOWS_HASH = "c3541f1a352003498979f2659c2570ac6dd227ec12533b75a76c4d109e75d218";
  private static final String PANDOC_MACOS_X8664_HASH = "72c43b1de30e67d3a2f69bfd69881e5fcf6ed3c2583c2ad22142c390d185f0b4";
  private static final String PANDOC_MACOS_ARM64_HASH = "aa0eab6cf10e5d54d255d68f8fae47e08da071565a3d2b8d242be29a8c1f1460";
  private static final String PANDOC_LINUX_AMD_HASH = "4e1c607f7e4e9243fa1e1f5b208cd4f1d3f6fd055d5d8c39ba0cdc38644e1c35";
  private static final String PANDOC_LINUX_ARM_HASH = "8ac04ce0aedae38f0c9f64bfe634910378cc326d091092395a2140a7ec819d54";

  private static final String DOWNLOAD_CACHE_DIRECTORY = "download-cache";
  // this is similar to the path where for example the grazie plugin places its dictionaries
  // content shouldn't be placed in the plugin's folder, as this will be replace upon plugin updates
  public static final String DOWNLOAD_PATH = PathManager.getSystemPath() + File.separator + DOWNLOAD_CACHE_DIRECTORY + File.separator + "asciidoctor-intellij-plugin";

  public static boolean downloadComplete() {
    return downloadCompleteAsciidoctorJPdf() && downloadCompleteAsciidoctorJDiagram();
  }

  public static boolean downloadCompleteAsciidoctorJDiagram() {
    return getAsciidoctorJDiagramFile().exists() && getAsciidoctorJDiagramPlantumlFile().exists() && getAsciidoctorJDiagramDitaaminiFile().exists();
  }

  public static boolean downloadCompleteAsciidoctorJPdf() {
    File file = getAsciidoctorJPdfFile();
    return file.exists();
  }

  public static File getAsciidoctorJPdfFile() {
    String fileName = DOWNLOAD_PATH + File.separator + "asciidoctorj-pdf-" + ASCIIDOCTORJ_PDF_VERSION + ".jar";
    return new File(fileName);
  }

  public static File getAsciidoctorJDiagramFile() {
    String fileName = DOWNLOAD_PATH + File.separator + "asciidoctorj-diagram-" + ASCIIDOCTORJ_DIAGRAM_VERSION + ".jar";
    return new File(fileName);
  }

  public static File getAsciidoctorJDiagramPlantumlFile() {
    String fileName = DOWNLOAD_PATH + File.separator + "asciidoctorj-diagram-plantuml-" + ASCIIDOCTORJ_DIAGRAM_PLANTUML_VERSION + ".jar";
    return new File(fileName);
  }

  public static File getAsciidoctorJDiagramDitaaminiFile() {
    String fileName = DOWNLOAD_PATH + File.separator + "asciidoctorj-diagram-ditaamini-" + ASCIIDOCTORJ_DIAGRAM_DITAAMINI_VERSION + ".jar";
    return new File(fileName);
  }

  public static void download(@Nullable Project project, @NotNull Runnable onSuccess, @NotNull Consumer<Throwable> onFailure) {
    downloadAsciidoctorJPdf(project, () -> downloadAsciidoctorJDiagram(project, onSuccess, onFailure), onFailure);
  }

  public static void downloadAsciidoctorJDiagram(@Nullable Project project, @NotNull Runnable onSuccess, @NotNull Consumer<Throwable> onFailure) {
    String downloadName = "asciidoctorj-diagram-" + ASCIIDOCTORJ_DIAGRAM_VERSION + ".jar";
    String url = getAsciidoctorJDiagramUrl();
    download(downloadName, url, ASCIIDOCTORJ_DIAGRAM_HASH, project, () -> downloadAsciidoctorJDiagramPlantuml(project, onSuccess, onFailure), onFailure);
  }

  public static void downloadAsciidoctorJDiagramPlantuml(@Nullable Project project, @NotNull Runnable onSuccess, @NotNull Consumer<Throwable> onFailure) {
    String downloadName = "asciidoctorj-diagram-plantuml-" + ASCIIDOCTORJ_DIAGRAM_PLANTUML_VERSION + ".jar";
    String url = getAsciidoctorJDiagramPlantumlUrl();
    download(downloadName, url, ASCIIDOCTORJ_DIAGRAM_PLANTUML_HASH, project, () -> downloadAsciidoctorJDiagramDitaamini(project, onSuccess, onFailure), onFailure);
  }

  public static void downloadAsciidoctorJDiagramDitaamini(@Nullable Project project, @NotNull Runnable onSuccess, @NotNull Consumer<Throwable> onFailure) {
    String downloadName = "asciidoctorj-diagram-ditaamini-" + ASCIIDOCTORJ_DIAGRAM_DITAAMINI_VERSION + ".jar";
    String url = getAsciidoctorJDiagramDitaaminiUrl();
    download(downloadName, url, ASCIIDOCTORJ_DIAGRAM_DITAAMINI_HASH, project, onSuccess, onFailure);
  }

  public static void downloadPandoc(@Nullable Project project, @NotNull Runnable onSuccess, @NotNull Consumer<Throwable> onFailure) {
    String downloadName = "pandoc-" + PANDOC_VERSION + "-" + archivePandoc();
    String url = getPandocUrl();
    download(downloadName, url, hashPandoc(), project, onSuccess, onFailure);
  }

  private static String hashPandoc() {
    if (SystemInfoRt.isWindows) {
      return PANDOC_WINDOWS_HASH;
    } else if (SystemInfoRt.isMac) {
      if (System.getProperty("os.arch").toLowerCase(Locale.ROOT).contains("arm")) {
        return PANDOC_MACOS_ARM64_HASH;
      } else {
        return PANDOC_MACOS_X8664_HASH;
      }

    } else if (SystemInfoRt.isLinux) {
      if (System.getProperty("os.arch").toLowerCase(Locale.ROOT).contains("arm")) {
        return PANDOC_LINUX_ARM_HASH;
      } else {
        return PANDOC_LINUX_AMD_HASH;
      }
    } else {
      throw new IllegalStateException("unsupported operating system: " + System.getProperty("os.name"));
    }
  }

  private static String archivePandoc() {
    if (SystemInfoRt.isWindows) {
      return "windows-x86_64.zip";
    } else if (SystemInfoRt.isMac) {
      String arch;
      if (System.getProperty("os.arch").toLowerCase(Locale.ROOT).contains("arm")) {
        arch = "arm64";
      } else {
        arch = "x86_64";
      }
      return arch + "-macOS.zip";
    } else if (SystemInfoRt.isLinux) {
      String arch;
      if (System.getProperty("os.arch").toLowerCase(Locale.ROOT).contains("arm")) {
        arch = "arm64";
      } else {
        arch = "amd64";
      }
      return "linux-" + arch + ".tar.gz";
    } else {
      throw new IllegalStateException("unsupported operating system: " + System.getProperty("os.name"));
    }
  }

  private static String executablePandoc() {
    if (SystemInfoRt.isWindows) {
      return "pandoc.exe";
    } else if (SystemInfoRt.isMac) {
      return "bin/pandoc";
    } else if (SystemInfoRt.isLinux) {
      return "bin/pandoc";
    } else {
      throw new IllegalStateException("unsupported operating system: " + System.getProperty("os.name"));
    }
  }

  public static void pickAsciidoctorJDiagram(@Nullable Project project, @NotNull Runnable onSuccess, @NotNull Consumer<Throwable> onFailure) {
    String downloadName = "asciidoctorj-diagram-" + ASCIIDOCTORJ_DIAGRAM_VERSION + ".jar";
    try {
      pickFile(downloadName, project, ASCIIDOCTORJ_DIAGRAM_HASH, onSuccess);
    } catch (IOException ex) {
      LOG.warn("Can't pick file '" + downloadName + "'", ex);
      ApplicationManager.getApplication().invokeLater(() -> onFailure.accept(ex));
    }
  }

  public static void pickAsciidoctorJDiagramPlantuml(@Nullable Project project, @NotNull Runnable onSuccess, @NotNull Consumer<Throwable> onFailure) {
    String downloadName = "asciidoctorj-diagram-plantuml-" + ASCIIDOCTORJ_DIAGRAM_PLANTUML_VERSION + ".jar";
    try {
      pickFile(downloadName, project, ASCIIDOCTORJ_DIAGRAM_PLANTUML_HASH, onSuccess);
    } catch (IOException ex) {
      LOG.warn("Can't pick file '" + downloadName + "'", ex);
      ApplicationManager.getApplication().invokeLater(() -> onFailure.accept(ex));
    }
  }

  public static void pickPandoc(@Nullable Project project, @NotNull Runnable onSuccess, @NotNull Consumer<Throwable> onFailure) {
    String downloadName = "pandoc-" + PANDOC_VERSION + "-" + archivePandoc();
    try {
      pickFile(downloadName, project, hashPandoc(), onSuccess);
    } catch (IOException ex) {
      LOG.warn("Can't pick file '" + downloadName + "'", ex);
      ApplicationManager.getApplication().invokeLater(() -> onFailure.accept(ex));
    }
  }

  public static void pickAsciidoctorJDiagramDitaamini(@Nullable Project project, @NotNull Runnable onSuccess, @NotNull Consumer<Throwable> onFailure) {
    String downloadName = "asciidoctorj-diagram-ditaamini-" + ASCIIDOCTORJ_DIAGRAM_DITAAMINI_VERSION + ".jar";
    try {
      pickFile(downloadName, project, ASCIIDOCTORJ_DIAGRAM_DITAAMINI_HASH, onSuccess);
    } catch (IOException ex) {
      LOG.warn("Can't pick file '" + downloadName + "'", ex);
      ApplicationManager.getApplication().invokeLater(() -> onFailure.accept(ex));
    }
  }

  public static void pickAsciidoctorJPdf(@Nullable Project project, @NotNull Runnable onSuccess, @NotNull Consumer<Throwable> onFailure) {
    String downloadName = "asciidoctorj-pdf-" + ASCIIDOCTORJ_PDF_VERSION + ".jar";
    try {
      pickFile(downloadName, project, ASCIIDOCTORJ_PDF_HASH, onSuccess);
    } catch (IOException ex) {
      LOG.warn("Can't pick file '" + downloadName + "'", ex);
      ApplicationManager.getApplication().invokeLater(() -> onFailure.accept(ex));
    }
  }

  public static String getAsciidoctorJDiagramUrl() {
    return "https://repo1.maven.org/maven2/org/asciidoctor/asciidoctorj-diagram/" +
      ASCIIDOCTORJ_DIAGRAM_VERSION +
      "/asciidoctorj-diagram-" +
      ASCIIDOCTORJ_DIAGRAM_VERSION +
      ".jar";
  }

  public static String getAsciidoctorJDiagramPlantumlUrl() {
    return "https://repo1.maven.org/maven2/org/asciidoctor/asciidoctorj-diagram-plantuml/" +
      ASCIIDOCTORJ_DIAGRAM_PLANTUML_VERSION +
      "/asciidoctorj-diagram-plantuml-" +
      ASCIIDOCTORJ_DIAGRAM_PLANTUML_VERSION +
      ".jar";
  }

  public static String getAsciidoctorJDiagramDitaaminiUrl() {
    return "https://repo1.maven.org/maven2/org/asciidoctor/asciidoctorj-diagram-ditaamini/" +
      ASCIIDOCTORJ_DIAGRAM_DITAAMINI_VERSION +
      "/asciidoctorj-diagram-ditaamini-" +
      ASCIIDOCTORJ_DIAGRAM_DITAAMINI_VERSION +
      ".jar";
  }

  public static String getPandocUrl() {
    return "https://github.com/jgm/pandoc/releases/download/" +
      PANDOC_VERSION +
      "/pandoc-" +
      PANDOC_VERSION +
      "-" +
      archivePandoc();
  }

  public static void downloadAsciidoctorJPdf(@Nullable Project project, @NotNull Runnable onSuccess, @NotNull Consumer<Throwable> onFailure) {
    String downloadName = "asciidoctorj-pdf-" + ASCIIDOCTORJ_PDF_VERSION + ".jar";
    String url = getAsciidoctorJPdfUrl();
    download(downloadName, url, ASCIIDOCTORJ_PDF_HASH, project, onSuccess, onFailure);
  }

  public static String getAsciidoctorJPdfUrl() {
    return "https://repo1.maven.org/maven2/org/asciidoctor/asciidoctorj-pdf/" +
      ASCIIDOCTORJ_PDF_VERSION +
      "/asciidoctorj-pdf-" +
      ASCIIDOCTORJ_PDF_VERSION +
      ".jar";
  }

  private static void download(String downloadName, String downloadUrl, String downloadHash, @Nullable Project project, @NotNull Runnable onSuccess, @NotNull Consumer<Throwable> onFailure) {
    File directory = new File(DOWNLOAD_PATH);
    if (!directory.exists()) {
      //noinspection ResultOfMethodCallIgnored
      directory.mkdirs();
    }

    String fileName = DOWNLOAD_PATH + File.separator + downloadName;
    File file = new File(fileName);
    if (file.exists()) {
      ApplicationManager.getApplication().invokeLater(onSuccess);
      return;
    }

    DownloadableFileService service = DownloadableFileService.getInstance();
    DownloadableFileDescription description = service.createFileDescription(downloadUrl, downloadName + ".part");
    FileDownloader downloader = service.createDownloader(Collections.singletonList(description), downloadName + ".part");

    Task.Backgroundable task = new Task.Backgroundable(project, AsciiDocBundle.message("asciidoc.download.task")) {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        try {
          List<VirtualFile> files = downloader.downloadFilesWithProgress(DOWNLOAD_PATH, project, null);
          if (files == null || files.size() == 0) {
            throw new IOException("Download failed");
          }
          File file = files.get(0).toNioPath().toFile();
          try (BufferedInputStream is = new BufferedInputStream(new FileInputStream(file))) {
            String hash = DigestUtils.sha256Hex(is);
            if (!downloadHash.equals(hash)) {
              throw new IOException("Hash of downloaded file doesn't match (expected: " + downloadHash + ", got: " + hash + ")");
            }
          }
          if (!file.renameTo(new File(fileName))) {
            throw new IOException("Unable to rename file '" + file.getAbsolutePath() + "' +  to + '" + downloadName + "'");
          }
          ApplicationManager.getApplication().invokeLater(() -> {
            ApplicationManager.getApplication().getMessageBus()
              .syncPublisher(AsciiDocApplicationSettings.SettingsChangedListener.TOPIC)
              .onSettingsChange(AsciiDocApplicationSettings.getInstance());
            onSuccess.run();
          });
        } catch (IOException e) {
          LOG.warn("Can't download content '" + downloadUrl + "' as '" + fileName + "'", e);
          createFailureNotification(e, true);
          ApplicationManager.getApplication().invokeLater(() -> onFailure.accept(e));
        }
      }

      private void createFailureNotification(IOException e, boolean download) {
        Notifications.Bus
          .notify(new Notification("asciidoctor", AsciiDocBundle.message("asciidoc.download.title"),
              (download ? AsciiDocBundle.message("asciidoc.download.failed") + ": " + "Can't download <a href=\"" + downloadUrl + "\">" + downloadUrl + "</a>" : "Copy failed. Can't copy " + downloadName) + " to folder " + directory.getAbsolutePath() + ": " + e.getMessage() + "."
                + (download ? "" : " If you haven't downloaded the file yet, you can download it from <a href=\"" + downloadUrl + "\">" + downloadUrl + "</a>"),
              NotificationType.ERROR)
              .setListener(new NotificationListener.UrlOpeningListener(false))
              .addAction(NotificationAction.createSimpleExpiring(
                "Retry download", () -> download(downloadName, downloadUrl, downloadHash, project, onSuccess, onFailure)))
              .addAction(NotificationAction.createSimpleExpiring(
                "Pick local file...", () -> {
                  try {
                    pickFile(downloadName, project, downloadHash, onSuccess);
                  } catch (IOException ex) {
                    LOG.warn("Can't pick file '" + downloadUrl + "' as '" + fileName + "'", ex);
                    createFailureNotification(ex, false);
                    ApplicationManager.getApplication().invokeLater(() -> onFailure.accept(ex));
                  }
                }
              )),
            project);
      }
    };
    BackgroundableProcessIndicator processIndicator = new BackgroundableProcessIndicator(task);
    processIndicator.setIndeterminate(false);
    ProgressManager.getInstance().runProcessWithProgressAsynchronously(task, processIndicator);
  }

  private static void pickFile(String downloadName, @Nullable Project project, String downloadHash, @NotNull Runnable onSuccess) throws IOException {
    File directory = new File(DOWNLOAD_PATH);
    if (!directory.exists()) {
      //noinspection ResultOfMethodCallIgnored
      directory.mkdirs();
    }
    @NotNull VirtualFile[] virtualFiles = FileChooser.chooseFiles(FileChooserDescriptorFactory.createSingleFileDescriptor()
        .withFileFilter(virtualFile -> virtualFile.getName().equals(downloadName))
        .withTitle("Pick Local File...")
        .withDescription("Please select file '" + downloadName + "' on your local disk."),
      project, null);
    if (virtualFiles.length == 1) {
      if (virtualFiles[0].isDirectory()) {
        throw new IOException("Directory selected. Please select a file named '" + downloadName + "'!");
      }
      if (!virtualFiles[0].getName().equals(downloadName)) {
        throw new IOException("Wrong file selected. Please select a file named '" + downloadName + "'!");
      }
      try (BufferedInputStream is = new BufferedInputStream(virtualFiles[0].getInputStream())) {
        String hash = DigestUtils.sha256Hex(is);
        if (!downloadHash.equals(hash)) {
          throw new IOException("Hash of selected file doesn't match (expected: " + ASCIIDOCTORJ_PDF_HASH + ", got: " + hash + ")");
        }
      }
      Path sourcePath = virtualFiles[0].getFileSystem().getNioPath(virtualFiles[0]);
      if (sourcePath == null) {
        throw new IOException("unable to so pick source path");
      }
      FileUtils.copyFile(sourcePath.toFile(), new File(directory, downloadName));
      ApplicationManager.getApplication().invokeLater(() -> {
        ApplicationManager.getApplication().getMessageBus()
          .syncPublisher(AsciiDocApplicationSettings.SettingsChangedListener.TOPIC)
          .onSettingsChange(AsciiDocApplicationSettings.getInstance());
        onSuccess.run();
      });
    } else {
      throw new IOException("No file selected. Please select a file named '" + downloadName + "'!");
    }
  }

  public static boolean downloadCompletePandoc() {
    return getPanddocFile().exists();
  }

  public static File getPanddocFile() {
    String archiveName = DOWNLOAD_PATH + File.separator + "pandoc-" + PANDOC_VERSION + "-" + archivePandoc();
    String fileName = DOWNLOAD_PATH + File.separator + "pandoc-" + PANDOC_VERSION + File.separator + executablePandoc();
    String destDir = DOWNLOAD_PATH;

    if (SystemInfoRt.isWindows || SystemInfoRt.isMac) {
      if (new File(archiveName).exists() && !new File(fileName).exists()) {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(archiveName))) {
          byte[] buffer = new byte[10240];
          ZipEntry zipEntry = zis.getNextEntry();
          while (zipEntry != null) {
            File newFile = new File(destDir, zipEntry.getName());
            if (zipEntry.isDirectory()) {
              if (!newFile.isDirectory() && !newFile.mkdirs()) {
                throw new IOException("Failed to create directory " + newFile);
              }
            } else {
              // fix for Windows-created archives
              File parent = newFile.getParentFile();
              if (!parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("Failed to create directory " + parent);
              }

              // write file content
              try (FileOutputStream fos = new FileOutputStream(newFile)) {
                int len;
                while ((len = zis.read(buffer)) > 0) {
                  fos.write(buffer, 0, len);
                }
              }

              if (SystemInfoRt.isMac) {
                if (Objects.equals(newFile, new File(fileName))) {
                  if (!newFile.setExecutable(true, true)) {
                    throw new IOException("can't make entry executable: " + newFile.getCanonicalPath());
                  }
                }
              }
            }
            zipEntry = zis.getNextEntry();
          }
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }
    } else if (SystemInfoRt.isLinux) {
      if (new File(archiveName).exists() && !new File(fileName).exists()) {
        try (InputStream fi = Files.newInputStream(Paths.get(archiveName));
             InputStream bi = new BufferedInputStream(fi);
             InputStream gzi = new GzipCompressorInputStream(bi)) {
          File targetDir = new File(destDir);
          try (ArchiveInputStream i = new TarArchiveInputStream(gzi)) {
            ArchiveEntry entry;
            while ((entry = i.getNextEntry()) != null) {
              if (!i.canReadEntryData(entry)) {
                // log something?
                continue;
              }
              File f = new File(targetDir, entry.getName());
              if (entry.isDirectory()) {
                if (!f.isDirectory() && !f.mkdirs()) {
                  throw new IOException("failed to create directory " + f);
                }
              } else {
                File parent = f.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) {
                  throw new IOException("failed to create directory " + parent);
                }
                try (OutputStream o = Files.newOutputStream(f.toPath())) {
                  IOUtils.copy(i, o);
                }
                if (Objects.equals(f, new File(fileName))) {
                  if (!f.setExecutable(true, true)) {
                    throw new IOException("can't make entry executable: " + f.getCanonicalPath());
                  }
                }
              }
            }
          }
        } catch (IOException ex) {
          throw new RuntimeException(ex);
        }
      }
    }
    return new File(fileName);
  }
}
