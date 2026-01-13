/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package my.ramses;

import java.io.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.commons.exec.OS;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

/**
 *
 * @author p3tris
 */
public class fileOps {

    public static void deleteFiles(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String fileName = args[i];
            // A File object to represent the filename
            File f = new File(fileName);

            // Make sure the file or directory exists and isn't write protected
            if (f.exists() && f.canWrite() && !f.isDirectory()) {
                boolean success = f.delete();
                if (!success) {
                    System.out.println("File " + f.getAbsolutePath() + " not deleted");
                }
            }

        }
    }

    public static boolean deleteDirectory(File directory) {

        // System.out.println("removeDirectory " + directory);

        if (directory == null) {
            return false;
        }
        if (!directory.exists()) {
            return true;
        }
        if (!directory.isDirectory()) {
            return false;
        }

        String[] list = directory.list();

        // Some JVMs return null for File.list() when the
        // directory is empty.
        if (list != null) {
            for (int i = 0; i < list.length; i++) {
                File entry = new File(directory, list[i]);

                //        System.out.println("\tremoving entry " + entry);

                if (entry.isDirectory()) {
                    if (!deleteDirectory(entry)) {
                        return false;
                    }
                } else {
                    if (!entry.delete()) {
                        return false;
                    }
                }
            }
        }

        return directory.delete();
    }

    public static File extractDyngraph(File tmpDir) throws IOException {
        File dyngraphExec = null;
        InputStream in = null;
        if (OS.isFamilyWindows()) {
            dyngraphExec = new File(tmpDir.getAbsolutePath() + System.getProperty("file.separator") + "dyngraph.exe");
//            if(System.getProperty("os.arch").toLowerCase(Locale.US).contains("64")){
                System.out.println("Extracting 64-bit dyngraph");
                in = RamsesUI.class.getResourceAsStream("dyngraph.exe");
//            } else {
//                in = RamsesUI.class.getResourceAsStream("dyngraph32.exe");
//                System.out.println("Extracting 32-bit dyngraph");
//            }
            
        } else {
            in = RamsesUI.class.getResourceAsStream("dyngraph");
            dyngraphExec = new File(tmpDir.getAbsolutePath() + System.getProperty("file.separator") + "dyngraph");
        }
        if (!dyngraphExec.exists()) {
            OutputStream streamOut = FileUtils.openOutputStream(dyngraphExec);
            IOUtils.copy(in, streamOut);
            in.close();
            streamOut.close();
            dyngraphExec.setExecutable(true);
        }
        return dyngraphExec;
    }

public static File extractCodegen(File tmpDir) throws IOException {
        File codegenExec = null;
        InputStream in = null;
        if (OS.isFamilyWindows()) {
            codegenExec = new File(tmpDir.getAbsolutePath() + System.getProperty("file.separator") + "codegen.exe");
            System.out.println("Extracting codegen");
            in = RamsesUI.class.getResourceAsStream("codegen.exe");
            
        } else {
            in = RamsesUI.class.getResourceAsStream("CODEGEN");
            codegenExec = new File(tmpDir.getAbsolutePath() + System.getProperty("file.separator") + "CODEGEN");
        }
        if (!codegenExec.exists()) {
            OutputStream streamOut = FileUtils.openOutputStream(codegenExec);
            IOUtils.copy(in, streamOut);
            in.close();
            streamOut.close();
            codegenExec.setExecutable(true);
        }
        return codegenExec;
    }

    public static File extractGnuplot(File tmpDir) throws IOException {
        File gnuplotExec;
        if (OS.isFamilyWindows()) {
            gnuplotExec = new File(tmpDir.getAbsolutePath() + System.getProperty("file.separator") +"gnuplot"
                    + System.getProperty("file.separator")+"bin"+System.getProperty("file.separator") + "pgnuplot.exe");
            if (!gnuplotExec.exists()) {
                InputStream in = RamsesUI.class.getResourceAsStream("gpwin.zip");
                ZipInputStream gpwinZip = new ZipInputStream(in);
                extractToFolder(gpwinZip, tmpDir);
            }
        } else {
            gnuplotExec = new File("/usr/bin/gnuplot");
        }
        return gnuplotExec;
    }
    
    public static File extractDoc(File tmpDir) throws IOException {
        File userguide;

        userguide = new File(tmpDir.getAbsolutePath() + System.getProperty("file.separator") +"DOC"
                + System.getProperty("file.separator") + "userguide.pdf");
        if (!userguide.exists()) {
//            URL onlDocZip = new URL("https://dl.dropbox.com/s/507utzvtcm2tlv6/DOC.zip");            
//            File docZip = new File(tmpDir.getAbsolutePath() + System.getProperty("file.separator") + "DOC.zip");
//            InputStream in = null;
//            try{
//                org.apache.commons.io.FileUtils.copyURLToFile(onlDocZip, docZip, 5000, 5000);
//                in = new FileInputStream(docZip);
//            } catch (IOException ex) {
//                in = RamsesUI.class.getResourceAsStream("DOC.zip");
//            }
            InputStream in = RamsesUI.class.getResourceAsStream("DOC.zip");
            extractToFolder(new ZipInputStream(in), tmpDir);
        }
        return userguide;
    }

    public static File extractNpp(File tmpDir) throws IOException {
        File npp;
        File nppdir;
        nppdir = new File(tmpDir.getAbsolutePath() + System.getProperty("file.separator") + "npp");
        nppdir.mkdir();
        npp = new File(nppdir.getAbsolutePath() + System.getProperty("file.separator") + "notepad++.exe");
        if (!npp.exists()) {
            InputStream in = RamsesUI.class.getResourceAsStream("npp.zip");
            ZipInputStream gpwinZip = new ZipInputStream(in);
            extractToFolder(gpwinZip, nppdir);
        }
        return npp;
    }

    public static File extractRamses(File tmpDir) throws IOException {
        File ramsesExec;
        if (OS.isFamilyWindows()) {
            ramsesExec = new File(tmpDir.getAbsolutePath() + System.getProperty("file.separator")+"dynsim"+ System.getProperty("file.separator") + "dynsim.exe");
        } else {
            ramsesExec = new File(tmpDir.getAbsolutePath() + System.getProperty("file.separator")+"dynsim"+ System.getProperty("file.separator") + "dynsim");
        }
        if (!ramsesExec.exists()) {
            InputStream in = RamsesUI.class.getResourceAsStream("dynsim.zip");
            ZipInputStream dynsimZip = new ZipInputStream(in);
            extractToFolder(dynsimZip, tmpDir);
            ramsesExec.setExecutable(true);
//            if (OS.isFamilyWindows()) {
//                //TODO
//            } else {
//                in = RamsesUI.class.getResourceAsStream("libklu.so");
//                File outKLU = new File(tmpDir.getAbsolutePath() + System.getProperty("file.separator") + "libklu.so");
//                OutputStream streamOut = FileUtils.openOutputStream(outKLU);
//                IOUtils.copy(in, streamOut);
//                outKLU.setExecutable(true);
//                in = RamsesUI.class.getResourceAsStream("libklu_Z.so");
//                File outKLUZ = new File(tmpDir.getAbsolutePath() + System.getProperty("file.separator") + "libklu_Z.so");
//                streamOut = FileUtils.openOutputStream(outKLUZ);
//                IOUtils.copy(in, streamOut);
//                in.close();
//                streamOut.close();
//                outKLUZ.setExecutable(true);
//            }
            
        }
        return ramsesExec;
    }
    
    public static File extractPfc(File tmpDir) throws IOException {
        File pfcExec = null;
        InputStream in = null;
        if (OS.isFamilyWindows()) {
            pfcExec = new File(tmpDir.getAbsolutePath() + System.getProperty("file.separator") + "PFC.exe");
//            if(System.getProperty("os.arch").toLowerCase(Locale.US).contains("64")){
                System.out.println("Extracting 64-bit PFC");
                in = RamsesUI.class.getResourceAsStream("PFC.exe");
//            } else {
//                in = RamsesUI.class.getResourceAsStream("dyngraph32.exe");
//                System.out.println("Extracting 32-bit dyngraph");
//            }
            
        } else {
            in = RamsesUI.class.getResourceAsStream("PFC");
            pfcExec = new File(tmpDir.getAbsolutePath() + System.getProperty("file.separator") + "PFC");
        }
        if (!pfcExec.exists()) {
            OutputStream streamOut = FileUtils.openOutputStream(pfcExec);
            IOUtils.copy(in, streamOut);
            in.close();
            streamOut.close();
            pfcExec.setExecutable(true);
        }
        return pfcExec;
    }
    
    public static File extractVswhere(File tmpDir) throws IOException {
        File vswhereExec = null;
        InputStream in = null;
        if (OS.isFamilyWindows()) {
            vswhereExec = new File(tmpDir.getAbsolutePath() + System.getProperty("file.separator") + "vswhere.exe");
//            if(System.getProperty("os.arch").toLowerCase(Locale.US).contains("64")){
                System.out.println("Extracting 64-bit vswhere");
                in = RamsesUI.class.getResourceAsStream("vswhere.exe");
//            } else {
//                in = RamsesUI.class.getResourceAsStream("dyngraph32.exe");
//                System.out.println("Extracting 32-bit dyngraph");
//            }
            
        } 
        if (!vswhereExec.exists()) {
            OutputStream streamOut = FileUtils.openOutputStream(vswhereExec);
            IOUtils.copy(in, streamOut);
            in.close();
            streamOut.close();
            vswhereExec.setExecutable(true);
        }
        return vswhereExec;
    }

    public static void extractToFolder(ZipInputStream zin, File outputFolderRoot)
            throws IOException {

        FileOutputStream fos = null;
        byte[] buf = new byte[1024];
        ZipEntry zipentry;

        for (zipentry = zin.getNextEntry(); zipentry != null; zipentry = zin.getNextEntry()) {

            try {
                String entryName = zipentry.getName();
                int n;

                File newFile = new File(outputFolderRoot, entryName);
                if (zipentry.isDirectory()) {
                    newFile.mkdirs();
                    continue;
                } else {
                    newFile.getParentFile().mkdirs();
                    newFile.createNewFile();
                }

                fos = new FileOutputStream(newFile);

                while ((n = zin.read(buf, 0, 1024)) > -1) {
                    fos.write(buf, 0, n);
                }

                fos.close();
                zin.closeEntry();
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (fos != null) {
                    try {
                        fos.close();
                    } catch (Exception ignore) {
                    }
                }
            }

        }

        zin.close();

    }

    public static void copyFiletoDir(File srcFile, File dstDir) throws IOException {
        if (srcFile.exists()) {
            FileInputStream streamIn = new FileInputStream(srcFile);
            File dstFile = new File(dstDir.getParent() + System.getProperty("file.separator") + srcFile.getName());
            OutputStream streamOut = FileUtils.openOutputStream(dstFile);
            IOUtils.copy(streamIn, streamOut);
            streamIn.close();
            streamOut.close();
        }
    }

    public static void copyFiletoFile(File srcFile, File dstFile) throws IOException {
        if (srcFile.exists()) {
            FileInputStream streamIn = new FileInputStream(srcFile);
            OutputStream streamOut = FileUtils.openOutputStream(dstFile);
            IOUtils.copy(streamIn, streamOut);
            streamIn.close();
            streamOut.close();
        }
    }
}
