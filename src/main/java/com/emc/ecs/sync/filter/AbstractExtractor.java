package com.emc.ecs.sync.filter;

import com.emc.ecs.sync.config.filter.AbstractExtractorConfig;
import com.emc.ecs.sync.model.*;
import com.emc.ecs.sync.storage.SyncStorage;
import com.emc.ecs.sync.storage.cas.CasStorage;
import com.emc.ecs.sync.util.LazyValue;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.TimeZone;
import java.util.WeakHashMap;

import static com.emc.ecs.sync.storage.file.AbstractFilesystemStorage.*;

public abstract class AbstractExtractor<C extends AbstractExtractorConfig> extends AbstractFilter<C> {
    private static final Logger log = LoggerFactory.getLogger(AbstractExtractor.class);

    private static final String NFS_DATE_FORMAT = "dd-MMM-yyyy HH:mm:ss";
    private static final ThreadLocal<WeakHashMap<String, DateFormat>> formatCache = new ThreadLocal<>();

    protected abstract InputStream getDataStream(SyncObject originalObject);

    @Override
    public void configure(SyncStorage source, Iterator<SyncFilter> filters, SyncStorage target) {
        super.configure(source, filters, target);

        if (source instanceof CasStorage) ((CasStorage) source).setDirectivesExpected(true);
    }

    @Override
    public void filter(ObjectContext objectContext) {
        final SyncObject origObject = objectContext.getObject();
        ObjectSummary summary = objectContext.getSourceSummary();
        ObjectMetadata metadata = origObject.getMetadata();

        if (summary.getListFileRow() == null)
            throw new RuntimeException("No list file data for " + summary.getIdentifier() + " (are you using the recursive option?)");

        // get filesystem metadata from list-file (in CSV format)
        // EXPECTED CSV FORMAT:
        // {source-id},{relative-path},"NFS",{uid},{gid},{mode},{mtime},{ctime},{atime}
        CSVRecord fileRecord = getListFileCsvRecord(summary.getListFileRow());

        // we at least need the relative path/name of the file (2nd field)
        if (fileRecord.size() < 2)
            throw new RuntimeException("No path info for " + summary.getIdentifier());

        // replace the raw source object with one representing the embedded file
        SyncObject extractedObject = new SyncObject(origObject.getSource(), fileRecord.get(1), metadata,
                origObject.getDataStream(), origObject.getAcl()) {
            // make sure we close the original object
            @Override
            public void close() throws Exception {
                origObject.close();
                super.close();
            }
        };
        objectContext.setObject(extractedObject);

        boolean symLink = false;

        // what if we don't have enough metadata fields in the source list file?
        if (fileRecord.size() < 6) {
            if (config.isFileMetadataRequired())
                throw new RuntimeException("File metadata not found for " + summary.getIdentifier());
            log.warn("File metadata not found for {}", summary.getIdentifier());

        } else { // we have metadata in source list file

            // third field will indicate it is NFS
            if (fileRecord.get(2).equals("NFS")) {
                String owner = "uid:" + fileRecord.get(3); // assume uid
                String group = "gid:" + fileRecord.get(4); // assume gid
                String permissions = fileRecord.get(5);

                symLink = permissions.startsWith("l"); // 'l' in the directory bit indicates a symlink
                if (symLink) {
                    // find link target
                    if (fileRecord.size() < 10)
                        throw new RuntimeException("No link target for symlink " + summary.getIdentifier());
                    String linkTarget = fileRecord.get(9);
                    extractedObject.getMetadata().setContentType(TYPE_LINK);
                    extractedObject.getMetadata().setUserMetadataValue(META_LINK_TARGET, linkTarget);
                }

                // normalize permission string (we only care about the last 9 characters)
                if (permissions.length() > 9) permissions = permissions.substring(permissions.length() - 9);

                Date mtime = null, ctime = null, atime = null;
                if (fileRecord.size() > 6) mtime = parse(fileRecord.get(6));
                else log.info("No mtime found for {}", summary.getIdentifier());
                if (fileRecord.size() > 7) ctime = parse(fileRecord.get(7));
                else log.info("No ctime found for {}", summary.getIdentifier());
                if (fileRecord.size() > 8) atime = parse(fileRecord.get(8));
                else log.info("No atime found for {}", summary.getIdentifier());

                // set file times
                log.debug("mtime: {}   ctime: {}   atime: {}", mtime, ctime, atime);
                metadata.setModificationTime(mtime);
                metadata.setMetaChangeTime(ctime);
                // NOTE: ctime (change time) and atime will be overwritten during update of the ACL...
                // the only thing we can reliably set here is mtime

                // extract POSIX ownership/mode
                ObjectAcl acl = new ObjectAcl();
                acl.setOwner(owner);
                if (permissions.charAt(0) != '-')
                    acl.addUserGrant(owner, fromPosixPermission(permissions.charAt(0)));
                if (permissions.charAt(1) != '-')
                    acl.addUserGrant(owner, fromPosixPermission(permissions.charAt(1)));
                if (permissions.charAt(2) != '-')
                    acl.addUserGrant(owner, fromPosixPermission(permissions.charAt(2)));

                if (permissions.charAt(3) != '-')
                    acl.addGroupGrant(group, fromPosixPermission(permissions.charAt(3)));
                if (permissions.charAt(4) != '-')
                    acl.addGroupGrant(group, fromPosixPermission(permissions.charAt(4)));
                if (permissions.charAt(5) != '-')
                    acl.addGroupGrant(group, fromPosixPermission(permissions.charAt(5)));

                if (permissions.charAt(6) != '-')
                    acl.addGroupGrant(OTHER_GROUP, fromPosixPermission(permissions.charAt(6)));
                if (permissions.charAt(7) != '-')
                    acl.addGroupGrant(OTHER_GROUP, fromPosixPermission(permissions.charAt(7)));
                if (permissions.charAt(8) != '-')
                    acl.addGroupGrant(OTHER_GROUP, fromPosixPermission(permissions.charAt(8)));

                extractedObject.setAcl(acl);
            }
        }

        // if this is a regular file, we need to extract the binary data
        if (!metadata.isDirectory() && !symLink) {

            // set (lazy) data stream as file data
            extractedObject.setDataStream(null);
            extractedObject.setLazyStream(new LazyValue<InputStream>() {
                @Override
                public InputStream get() {
                    return getDataStream(origObject);
                }
            });
        }

        // continue the filter chain
        getNext().filter(objectContext);
    }

    @Override
    public SyncObject reverseFilter(ObjectContext objectContext) {
        // the verifier should have our modified source object, which will match
        return getNext().reverseFilter(objectContext);
    }

    private static String fromPosixPermission(char fp) {
        switch (fp) {
            case 'r':
                return READ;
            case 'w':
                return WRITE;
            case 'x':
                return EXECUTE;
            default:
                throw new IllegalArgumentException("unknown POSIX permission: " + fp);
        }
    }

    public static Date parse(String string) {
        if (string == null) return null;

        DateFormat df = getFormat(NFS_DATE_FORMAT);
        try {
            Date d = df.parse(string);
            log.debug("parsed date [{}] to millis: {}", string, d.getTime());
            return d;
        } catch (ParseException e) {
            throw new RuntimeException("Could not parse date: " + string, e);
        }
    }

    private static DateFormat getFormat(String formatString) {
        if (formatCache.get() == null) {
            formatCache.set(new WeakHashMap<String, DateFormat>());
        }

        DateFormat format = formatCache.get().get(formatString);
        if (format == null) {
            format = new SimpleDateFormat(formatString);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            formatCache.get().put(formatString, format);
        }
        return format;
    }
}