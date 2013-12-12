package net.minecraftforge.gradle.tasks.dev;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import net.md_5.specialsource.Jar;
import net.md_5.specialsource.JarMapping;
import net.md_5.specialsource.JarRemapper;
import net.md_5.specialsource.provider.ClassLoaderProvider;
import net.md_5.specialsource.provider.JarProvider;
import net.md_5.specialsource.provider.JointProvider;
import net.minecraftforge.gradle.delayed.DelayedFile;
import net.minecraftforge.gradle.dev.FmlDevPlugin;

import org.gradle.api.DefaultTask;
import org.gradle.api.Project;
import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.AbstractTask;
import org.gradle.api.tasks.TaskAction;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Opcodes;

import static org.objectweb.asm.Opcodes.*;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;
import com.google.common.io.LineProcessor;

import de.oceanlabs.mcp.mcinjector.StringUtil;

public class ObfuscateTask extends DefaultTask
{
    private DelayedFile outJar;
    private DelayedFile srg;
    private DelayedFile exc;
    private boolean     reverse;
    private DelayedFile buildFile;

    @TaskAction
    public void doTask() throws IOException
    {
        getLogger().debug("Building child project model...");
        Project childProj = FmlDevPlugin.getProject(getBuildFile(), getProject());
        AbstractTask compileTask = (AbstractTask) childProj.getTasks().getByName("compileJava");
        AbstractTask jarTask = (AbstractTask) childProj.getTasks().getByName("jar");

        // executing jar task
        getLogger().debug("Executing child Jar task...");
        executeTask(jarTask);
        
        File inJar = (File)jarTask.property("archivePath");

        File srg = getSrg();

        if (getExc() != null)
        {
            Map<String, String> recomp = Maps.newHashMap();
            List<String> interfaces = Lists.newArrayList();
            readMarkers(inJar, recomp, interfaces);
            srg = createSrg(srg, getExc(), recomp, interfaces);
        }

        getLogger().debug("Obfuscating jar...");
        obfuscate(inJar, (FileCollection)compileTask.property("classpath"), srg);
    }
    
    private void readMarkers(File inJar, final Map<String, String> map, final List<String> intList) throws IOException
    {
        ZipInputStream zip = null;
        try
        {
            try
            {
                zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(inJar)));
            }
            catch (FileNotFoundException e)
            {
                throw new FileNotFoundException("Could not open input file: " + e.getMessage());
            }

            ClassVisitor reader = new ClassVisitor(Opcodes.ASM4, null)
            {
                private String className;

                @Override
                public void visit(int version, int access, String name, String signature, String superName, String[] interfaces)
                {
                    this.className = name;;
                    if ((access & ACC_INTERFACE) == ACC_INTERFACE)
                        intList.add(className);
                }

                @Override
                public FieldVisitor visitField(int access, String name, String desc, String signature, Object value)
                {
                    if (name.equals("__OBFID"))
                    {
                        map.put(String.valueOf(value) + "_", className);
                    }
                    return null;
                }
            };
            
            while (true)
            {
                ZipEntry entry = zip.getNextEntry();
                if (entry == null) break;
                if (entry.isDirectory() ||
                    !entry.getName().endsWith(".class")) continue;
                (new ClassReader(ByteStreams.toByteArray(zip))).accept(reader, 0);
            }
        } finally {
            if (zip != null) {
                try {
                    zip.close();
                } catch (IOException e){}
            }
        }
    }

    private void executeTask(AbstractTask task)
    {
        for (Object dep : task.getTaskDependencies().getDependencies(task))
        {
            executeTask((AbstractTask) dep);
        }

        if (!task.getState().getExecuted())
        {
            getLogger().lifecycle(task.getPath());
            task.execute();
        }
    }

    private void obfuscate(File inJar, FileCollection classpath, File srg) throws FileNotFoundException, IOException
    {
        // load mapping
        JarMapping mapping = new JarMapping();
        mapping.loadMappings(Files.newReader(srg, Charset.defaultCharset()), null, null, reverse);

        // make remapper
        JarRemapper remapper = new JarRemapper(null, mapping);

        // load jar
        Jar input = Jar.init(inJar);

        // ensure that inheritance provider is used
        JointProvider inheritanceProviders = new JointProvider();
        inheritanceProviders.add(new JarProvider(input));

        if (classpath != null)
            inheritanceProviders.add(new ClassLoaderProvider(new URLClassLoader(toUrls(classpath))));

        mapping.setFallbackInheritanceProvider(inheritanceProviders);

        File out = getOutJar();
        if (!out.getParentFile().exists()) //Needed because SS doesn't create it.
        {
            out.getParentFile().mkdirs();
        }

        // remap jar
        remapper.remapJar(input, getOutJar());
    }

    private File createSrg(File base, File exc, Map<String, String> markerMap, final List<String> intList) throws IOException
    {
        File srg = new File(this.getTemporaryDir(), "reobf_cls.srg");
        if (srg.isFile())
            srg.delete();

        Map<String, String> excMap = Files.readLines(exc, Charset.defaultCharset(), new LineProcessor<Map<String, String>>()
        {
            Map<String, String> tmp = Maps.newHashMap();

            @Override
            public boolean processLine(String line) throws IOException
            {
                if (line.contains(".") ||
                   !line.contains("=") ||
                    line.startsWith("#")) return true;

                String[] s = line.split("=");
                tmp.put(s[0], s[1] + "_");

                return true;
            }

            @Override
            public Map<String, String> getResult()
            {
                return tmp;
            }
        });
        Map<String, String> map = Maps.newHashMap();
        for (Entry<String, String> e : excMap.entrySet())
        {
            String renamed = markerMap.get(e.getValue());
            if (renamed != null)
            {
                map.put(e.getKey(), renamed);
            }
        }

        String fixed = Files.readLines(base, Charset.defaultCharset(), new SrgLineProcessor(map));
        Files.write(fixed.getBytes(), srg);
        return srg;
    }

    private static class SrgLineProcessor implements LineProcessor<String>
    {
        Map<String, String> map;
        StringBuilder out = new StringBuilder();
        Pattern reg = Pattern.compile("L([^;]+);");

        private SrgLineProcessor(Map<String, String> map)
        {
            this.map = map;
        }

        private String rename(String cls)
        {
            String rename = map.get(cls);
            return rename == null ? cls : rename;
        }

        private String[] rsplit(String value, String delim)
        {
            int idx = value.lastIndexOf(delim);
            return new String[]
            {
                value.substring(0, idx),
                value.substring(idx + 1)
            };
        }

        @Override
        public boolean processLine(String line) throws IOException
        {
            String[] split = line.split(" ");
            if (split[0].equals("CL:"))
            {
                split[2] = rename(split[2]);
            }
            else if (split[0].equals("FD:"))
            {
                String[] s = rsplit(split[2], "/");
                split[2] = rename(s[0]) + "/" + s[1];
            }
            else if (split[0].equals("MD:"))
            {
                String[] s = rsplit(split[3], "/");
                split[3] = rename(s[0]) + "/" + s[1];

                Matcher m = reg.matcher(split[4]);
                StringBuffer b = new StringBuffer();
                while(m.find())
                {
                    m.appendReplacement(b, "L" + rename(m.group(1)).replace("$",  "\\$") + ";");
                }
                m.appendTail(b);
                split[4] = b.toString();
            }
            out.append(StringUtil.joinString(Arrays.asList(split), " ")).append('\n');
            return true;
        }

        @Override
        public String getResult()
        {
            return out.toString();
        }

    }

    public static URL[] toUrls(FileCollection collection) throws MalformedURLException
    {
        ArrayList<URL> urls = new ArrayList<URL>();

        for (File file : collection.getFiles())
            urls.add(file.toURI().toURL());

        return urls.toArray(new URL[urls.size()]);
    }

    public File getOutJar()
    {
        return outJar.call();
    }

    public void setOutJar(DelayedFile outJar)
    {
        this.outJar = outJar;
    }

    public File getSrg()
    {
        return srg.call();
    }

    public void setSrg(DelayedFile srg)
    {
        this.srg = srg;
    }

    public File getExc()
    {
        return exc.call();
    }

    public void setExc(DelayedFile exc)
    {
        this.exc = exc;
    }

    public boolean isReverse()
    {
        return reverse;
    }

    public void setReverse(boolean reverse)
    {
        this.reverse = reverse;
    }

    public File getBuildFile()
    {
        return buildFile.call();
    }

    public void setBuildFile(DelayedFile buildFile)
    {
        this.buildFile = buildFile;
    }
}
