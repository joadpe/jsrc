package com.javautil.app.codebase;

import java.nio.file.Path;
import java.util.List;

public interface CodeBase {
    
    public void setPath(String path);
    public List<Path> getFiles();
}
