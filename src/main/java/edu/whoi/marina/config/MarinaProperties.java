package edu.whoi.marina.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "marina")
public class MarinaProperties {

    private String dataDir = "./data";
    private Import importCfg = new Import();

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }

    public Import getImport() {
        return importCfg;
    }

    public void setImport(Import importCfg) {
        this.importCfg = importCfg;
    }

    public static class Import {
        private String sourceXlsx = "./Dock Schedule - Synthetic Sample.xlsx";
        private boolean forceReimport = false;

        public String getSourceXlsx() {
            return sourceXlsx;
        }

        public void setSourceXlsx(String sourceXlsx) {
            this.sourceXlsx = sourceXlsx;
        }

        public boolean isForceReimport() {
            return forceReimport;
        }

        public void setForceReimport(boolean forceReimport) {
            this.forceReimport = forceReimport;
        }
    }
}
