package com.bloomreach.xm.manager.s3.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.cxf.jaxrs.ext.multipart.Attachment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import com.amazonaws.HttpMethod;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.CannedAccessControlList;
import com.amazonaws.services.s3.model.CompleteMultipartUploadRequest;
import com.amazonaws.services.s3.model.DeleteObjectsRequest;
import com.amazonaws.services.s3.model.DeleteObjectsResult;
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadResult;
import com.amazonaws.services.s3.model.ListObjectsV2Request;
import com.amazonaws.services.s3.model.ListObjectsV2Result;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PartETag;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.PutObjectResult;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.amazonaws.services.s3.model.UploadPartRequest;
import com.amazonaws.services.s3.model.UploadPartResult;
import com.bloomreach.xm.manager.api.ListItem;
import com.bloomreach.xm.manager.api.Type;
import com.bloomreach.xm.manager.s3.model.S3ListItem;

public class AwsS3Service {

    private static final Logger logger = LoggerFactory.getLogger(AwsS3Service.class);
    private final String bucket;
    private final AmazonS3 amazonS3;
    private final Map<String, InitiateMultipartUploadResult> multipartUploadResultMap = new HashMap<>();
    private final MultiValueMap<String, PartETag> eParts = new LinkedMultiValueMap();

    public AwsS3Service(final AwsService awsService, final String bucket) {
        this.bucket = bucket;
        amazonS3 = awsService.getS3client();
    }

    public String generatePresignedUrl(String key) {
        java.util.Date expiration = new java.util.Date();
        long expTimeMillis = expiration.getTime();
        expTimeMillis += 1000 * 60 * 60;
        expiration.setTime(expTimeMillis);

        GeneratePresignedUrlRequest generatePresignedUrlRequest =
                new GeneratePresignedUrlRequest(bucket, key)
                        .withMethod(HttpMethod.GET)
                        .withExpiration(expiration);
        final URL url = amazonS3.generatePresignedUrl(generatePresignedUrlRequest);
        return url.toString();
    }

    public List<S3ListItem> getList(final String prefix, final String query) {
        ListObjectsV2Request listObjectsRequest = new ListObjectsV2Request()
                .withBucketName(bucket)
                .withPrefix(prefix)
                .withDelimiter("/");

        ListObjectsV2Result objectListing = amazonS3.listObjectsV2(listObjectsRequest);

        List<S3ListItem> objects = objectListing.getObjectSummaries().stream().filter(s3ObjectSummary -> !s3ObjectSummary.getKey().equals(prefix)).map(s3ObjectSummary -> new S3ListItem(s3ObjectSummary, amazonS3.getUrl(bucket, s3ObjectSummary.getKey()).toString())).collect(Collectors.toList());
        List<S3ListItem> folders = objectListing.getCommonPrefixes().stream().map(S3ListItem::new).collect(Collectors.toList());
        return Stream.concat(folders.stream(), objects.stream()).filter(s3ListItem -> StringUtils.isEmpty(query) || s3ListItem.getName().toLowerCase().contains(query.toLowerCase())).collect(Collectors.toList());
    }

    public void createFolder(String key) {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentType("binary/octet-stream");
        PutObjectRequest putRequest = new PutObjectRequest(bucket, key + "/", new ByteArrayInputStream(new byte[0]), metadata);
        PutObjectResult putObjectResult = amazonS3.putObject(putRequest);
    }

    public void deleteFiles(List<S3ListItem> items) {
        DeleteObjectsRequest deleteObjectsRequestFiles = new DeleteObjectsRequest(bucket);
        String[] filesKeys = items.stream().filter(s3ListItem -> s3ListItem.getType().equals(Type.FILE)).map(ListItem::getId).toArray(String[]::new);
        if (filesKeys.length > 0) {
            deleteObjectsRequestFiles.withKeys(filesKeys);
            DeleteObjectsResult deleteObjectsResultFiles = amazonS3.deleteObjects(deleteObjectsRequestFiles);
        }
        items.stream().filter(s3ListItem -> s3ListItem.getType().equals(Type.FOLDER)).map(S3ListItem::getId).forEach(this::deleteDirectory);
    }

    public void deleteDirectory(String prefix) {
        ObjectListing objectList = amazonS3.listObjects(bucket, prefix);
        String[] keysList = objectList.getObjectSummaries().stream().map(S3ObjectSummary::getKey).toArray(String[]::new);
        DeleteObjectsRequest deleteObjectsRequest = new DeleteObjectsRequest(bucket).withKeys(keysList);
        amazonS3.deleteObjects(deleteObjectsRequest);
    }

    //for small files
    public void uploadSinglepart(Attachment multipartFile, final String path) {
        String uniqueFileName = path + multipartFile.getDataHandler().getName();
        PutObjectRequest por = null;
        try {
            byte[] bytes = IOUtils.toByteArray(multipartFile.getDataHandler().getInputStream());
            String contentType = multipartFile.getContentType().toString();
            ObjectMetadata objectMetadata = new ObjectMetadata();
            objectMetadata.setContentType(contentType);
            objectMetadata.setContentLength(bytes.length);
            ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(bytes);
            por = new PutObjectRequest(bucket, uniqueFileName, byteArrayInputStream, objectMetadata).withCannedAcl(CannedAccessControlList.PublicRead);
        } catch (IOException e) {
            logger.error("Some error.", e);
        }
        amazonS3.putObject(por);
    }

    //for large files
    public void uploadMultipart(Attachment multipartFile, final String path, final int index, final int total) {
        String uniqueFileName = path + multipartFile.getDataHandler().getName();

        if (!multipartUploadResultMap.containsKey(uniqueFileName)) {
            InitiateMultipartUploadRequest initRequest = new InitiateMultipartUploadRequest(bucket, uniqueFileName).withCannedACL(CannedAccessControlList.PublicRead);
            InitiateMultipartUploadResult initResponse = amazonS3.initiateMultipartUpload(initRequest);
            multipartUploadResultMap.put(uniqueFileName, initResponse);
        }

        InitiateMultipartUploadResult initResponse = multipartUploadResultMap.get(uniqueFileName);

        int partNumber = index + 1;

        UploadPartRequest uploadRequest = null;
        try {
            byte[] bytes = IOUtils.toByteArray(multipartFile.getDataHandler().getInputStream());
            ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(bytes);
            uploadRequest = new UploadPartRequest()
                    .withBucketName(bucket)
                    .withKey(uniqueFileName)
                    .withUploadId(initResponse.getUploadId())
                    .withPartNumber(partNumber)
                    .withInputStream(byteArrayInputStream)
                    .withPartSize(bytes.length);
        } catch (IOException e) {
            e.printStackTrace();
        }
//        // Upload the part and add the response's ETag to our list.
        UploadPartResult uploadResult = amazonS3.uploadPart(uploadRequest);
        eParts.add(uniqueFileName, uploadResult.getPartETag());

        if (partNumber == total) {
            CompleteMultipartUploadRequest compRequest = new CompleteMultipartUploadRequest(bucket, uniqueFileName,
                    initResponse.getUploadId(), eParts.get(uniqueFileName));
            amazonS3.completeMultipartUpload(compRequest);
            clearMultipartUpload(uniqueFileName);
        }
    }

    private void clearMultipartUpload(final String uniqueFileName) {
        multipartUploadResultMap.remove(uniqueFileName);
        eParts.remove(uniqueFileName);
    }

}