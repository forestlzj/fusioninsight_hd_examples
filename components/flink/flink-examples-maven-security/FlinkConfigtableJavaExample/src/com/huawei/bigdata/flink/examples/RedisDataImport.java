package com.huawei.bigdata.flink.examples;

import com.huawei.bigdata.security.LoginUtil;
import org.apache.flink.api.java.utils.ParameterTool;

import org.supercsv.cellprocessor.constraint.NotNull;
import org.supercsv.cellprocessor.ift.CellProcessor;
import org.supercsv.io.CsvBeanReader;
import org.supercsv.io.ICsvBeanReader;
import org.supercsv.prefs.CsvPreference;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisCluster;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;

/**
 * Read data from csv file and import to redis.
 */
public class RedisDataImport
{
    public static void main(String[] args) throws Exception
    {
        // print comment for command to use run flink
        System.out.println("use command as: \n" + "java -cp /opt/FI-Client/Flink/flink/lib/*:/opt/FlinkConfigtableJavaExample.jar" + " com.huawei.bigdata.flink.examples.RedisDataImport --configPath <config filePath>" + "******************************************************************************************\n" + "<config filePath> is for configure file to load\n" + "you may write following content into config filePath: \n" + "CsvPath=config/configtable.csv\n" + "CsvHeaderExist=true\n" + "ColumnNames=username,age,company,workLocation,educational,workYear,phone,nativeLocation,school\n" + "Redis_Security=true\n" + "Redis_IP_Port=SZV1000064084:22400,SZV1000064082:22400,SZV1000064085:22400\n" + "Redis_Principal=test11@HADOOP.COM\n" + "Redis_KeytabFile=config/user.keytab\n" + "Redis_Krb5File=config/krb5.conf\n" + "******************************************************************************************");

        // read all configures
        final String configureFilePath = ParameterTool.fromArgs(args).get("configPath", "config/import.properties");
        final String csvFilePath = ParameterTool.fromPropertiesFile(configureFilePath).get("CsvPath", "config/configtable.csv");
        final boolean isHasHeaders = ParameterTool.fromPropertiesFile(configureFilePath).getBoolean("CsvHeaderExist", true);
        final String csvScheme = ParameterTool.fromPropertiesFile(configureFilePath).get("ColumnNames");
        final boolean isSecurity = ParameterTool.fromPropertiesFile(configureFilePath).getBoolean("Redis_Security", true);
        final String redisIPPort = ParameterTool.fromPropertiesFile(configureFilePath).get("Redis_IP_Port");
        final String principal = ParameterTool.fromPropertiesFile(configureFilePath).get("Redis_Principal");
        final String keytab = ParameterTool.fromPropertiesFile(configureFilePath).get("Redis_KeytabFile");
        final String krb5 = ParameterTool.fromPropertiesFile(configureFilePath).get("Redis_Krb5File");

        // init redis client
        initRedis(isSecurity, principal, keytab, krb5);
        Set<HostAndPort> hosts = new HashSet<HostAndPort>();
        for (String hostAndPort : redisIPPort.split(","))
        {
            hosts.add(new HostAndPort(hostAndPort.split(":")[0], Integer.parseInt(hostAndPort.split(":")[1])));
        }
        final JedisCluster client = new JedisCluster(hosts, 15000);

        // get all files under csv file path
        ArrayList<File> files = getListFiles(csvFilePath);
        System.out.println("Read file or directory under  " + csvFilePath + ", total file num: " + files.size() + ", columns: " + csvScheme);

        // run read csv file and analyze it
        for (int index = 0; index < files.size(); index++)
        {
            readWithCsvBeanReader(files.get(index).getAbsolutePath(), csvScheme, isHasHeaders, client);
        }
        client.close();
        System.out.println("Data import finish!!!");
    }

    public static void initRedis(boolean isRedisSecurity, String userPrincipal, String keytabPath, String krb5Path) throws IOException
    {
        // redis security
        System.setProperty("redis.authentication.jaas", isRedisSecurity ? "true" : "false");

        // check and set
        if (System.getProperty("redis.authentication.jaas", "false").equals("true"))
        {
            LoginUtil.setJaasFile(userPrincipal, keytabPath);
            LoginUtil.setKrb5Config(krb5Path);
            System.setProperty("SERVER_REALM", "hadoop.com");
        }
    }

    public static ArrayList<File> getListFiles(Object obj)
    {
        File directory = null;
        if (obj instanceof File)
        {
            directory = (File) obj;
        }
        else
        {
            directory = new File(obj.toString());
        }
        ArrayList<File> files = new ArrayList<File>();
        if (directory.isFile())
        {
            files.add(directory);
            return files;
        }
        else if (directory.isDirectory())
        {
            File[] fileArr = directory.listFiles();
            for (int i = 0; i < fileArr.length; i++)
            {
                File fileOne = fileArr[i];
                files.addAll(getListFiles(fileOne));
            }
        }
        return files;
    }

    /**
     * Sets up the processors used for read csv. There are 9 CSV columns. Empty
     * columns are read as null (hence the NotNull() for mandatory columns).
     *
     * @return the cell processors
     */
    private static CellProcessor[] getProcessors()
    {
        final CellProcessor[] processors = new CellProcessor[]{
                new NotNull(), // username
                new NotNull(), // age
                new NotNull(), // company
                new NotNull(), // workLocation
                new NotNull(), // educational
                new NotNull(), // workYear
                new NotNull(), // phone
                new NotNull(), // nativeLocation
                new NotNull(), // school
        };

        return processors;
    }

    private static void readWithCsvBeanReader(String path, String csvScheme, boolean isSkipHeader, JedisCluster client) throws Exception
    {
        ICsvBeanReader beanReader = null;
        try
        {
            beanReader = new CsvBeanReader(new FileReader(path), CsvPreference.STANDARD_PREFERENCE);

            // the header elements are used to map the values to the bean (names must match)
            final String[] header = isSkipHeader ? beanReader.getHeader(true) : csvScheme.split(",");
            final CellProcessor[] processors = getProcessors();

            UserInfo userinfo;
            while ((userinfo = beanReader.read(UserInfo.class, header, processors)) != null)
            {
                System.out.println(String.format("lineNo=%s, rowNo=%s, userinfo=%s", beanReader.getLineNumber(), beanReader.getRowNumber(), userinfo));

                // set redis key and value
                client.hmset(userinfo.getKeyValue(), userinfo.getMapInfo());
            }
        }
        finally
        {
            if (beanReader != null)
            {
                beanReader.close();
            }
        }
    }


    // define the UserInfo structure
    public static class UserInfo
    {
        private String username;
        private String age;
        private String company;
        private String workLocation;
        private String educational;
        private String workYear;
        private String phone;
        private String nativeLocation;
        private String school;


        public UserInfo()
        {

        }

        public UserInfo(String nm, String a, String c, String w, String e, String wy, String p, String nl, String sc)
        {
            username = nm;
            age = a;
            company = c;
            workLocation = w;
            educational = e;
            workYear = wy;
            phone = p;
            nativeLocation = nl;
            school = sc;
        }

        public String toString()
        {
            return "UserInfo-----[username: " + username + "  age: " + age + "  company: " + company + "  workLocation: " + workLocation + "  educational: " + educational + "  workYear: " + workYear + "  phone: " + phone + "  nativeLocation: " + nativeLocation + "  school: " + school + "]";
        }

        // get key
        public String getKeyValue()
        {
            return username;
        }

        public Map<String, String> getMapInfo()
        {
            Map<String, String> info = new HashMap<String, String>();
            info.put("username", username);
            info.put("age", age);
            info.put("company", company);
            info.put("workLocation", workLocation);
            info.put("educational", educational);
            info.put("workYear", workYear);
            info.put("phone", phone);
            info.put("nativeLocation", nativeLocation);
            info.put("school", school);
            return info;
        }

        /**
         * @return the username
         */
        public String getUsername()
        {
            return username;
        }

        /**
         * @param username the username to set
         */
        public void setUsername(String username)
        {
            this.username = username;
        }

        /**
         * @return the age
         */
        public String getAge()
        {
            return age;
        }

        /**
         * @param age the age to set
         */
        public void setAge(String age)
        {
            this.age = age;
        }

        /**
         * @return the company
         */
        public String getCompany()
        {
            return company;
        }

        /**
         * @param company the company to set
         */
        public void setCompany(String company)
        {
            this.company = company;
        }

        /**
         * @return the workLocation
         */
        public String getWorkLocation()
        {
            return workLocation;
        }

        /**
         * @param workLocation the workLocation to set
         */
        public void setWorkLocation(String workLocation)
        {
            this.workLocation = workLocation;
        }

        /**
         * @return the educational
         */
        public String getEducational()
        {
            return educational;
        }

        /**
         * @param educational the educational to set
         */
        public void setEducational(String educational)
        {
            this.educational = educational;
        }

        /**
         * @return the workYear
         */
        public String getWorkYear()
        {
            return workYear;
        }

        /**
         * @param workYear the workYear to set
         */
        public void setWorkYear(String workYear)
        {
            this.workYear = workYear;
        }

        /**
         * @return the phone
         */
        public String getPhone()
        {
            return phone;
        }

        /**
         * @param phone the phone to set
         */
        public void setPhone(String phone)
        {
            this.phone = phone;
        }

        /**
         * @return the nativeLocation
         */
        public String getNativeLocation()
        {
            return nativeLocation;
        }

        /**
         * @param nativeLocation the nativeLocation to set
         */
        public void setNativeLocation(String nativeLocation)
        {
            this.nativeLocation = nativeLocation;
        }

        /**
         * @return the school
         */
        public String getSchool()
        {
            return school;
        }

        /**
         * @param school the school to set
         */
        public void setSchool(String school)
        {
            this.school = school;
        }
    }
}
