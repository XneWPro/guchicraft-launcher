package ru.guchicraft.common.manifest;
import java.util.List;
public record ClientManifest(int manifestVersion,String buildVersion,String minecraftVersion,String fabricLoaderVersion,JavaConfiguration java,LaunchConfiguration launch,String serverAddress,List<String> managedDirectories,boolean removeUnknownFiles,List<FileEntry> files){
 public ClientManifest{java=java==null?new JavaConfiguration(21):java;launch=launch==null?new LaunchConfiguration(2048,4096,16384):launch;managedDirectories=managedDirectories==null?List.of():List.copyOf(managedDirectories);files=files==null?List.of():List.copyOf(files);}
 public record JavaConfiguration(int majorVersion){}
 public record LaunchConfiguration(int minimumMemoryMb,int defaultMemoryMb,int maximumMemoryMb){public LaunchConfiguration{if(minimumMemoryMb<=0)minimumMemoryMb=2048;if(defaultMemoryMb<minimumMemoryMb)defaultMemoryMb=minimumMemoryMb;if(maximumMemoryMb<defaultMemoryMb)maximumMemoryMb=defaultMemoryMb;}}
 public record FileEntry(String path,String url,String sha256,long size){}
}
