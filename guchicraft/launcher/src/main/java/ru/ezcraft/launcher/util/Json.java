package ru.ezcraft.launcher.util;
import com.fasterxml.jackson.databind.*;
public final class Json { public static final ObjectMapper MAPPER=new ObjectMapper().findAndRegisterModules(); private Json(){} }
