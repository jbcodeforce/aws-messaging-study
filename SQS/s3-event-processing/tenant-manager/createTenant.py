import boto3,os,datetime,json
import sys,getopt

TENANT_GROUP_NAME="tenant-group-1"
TENANT_GROUP_TABLE_NAME="TenantGroups"
TENANTS_TABLE_NAME="Tenants"
TENANT_NAME="tenant-1"
ACCOUNT = os.environ.get('AWS_ACCOUNT_ID')
REGION = os.environ.get('AWS_DEFAULT_REGION')

s3 = boto3.client('s3')
dynamodb = boto3.client('dynamodb')

def loadTenantGroupInformation(keyName):
    tg = dynamodb.get_item(TableName=TENANT_GROUP_TABLE_NAME, Key={'GroupName': {'S': keyName}})   
    return tg['Item']

def loadTenant(keyName):
    tg = dynamodb.get_item(TableName=TENANTS_TABLE_NAME, Key={'Name': {'S': keyName}})   
    return tg['Item']

"""
Create a tenant belonging to a group. The demo is scoped per region.
"""   
def getOrCreateTenant(tenantName,tenantGroupInfo):
    tenant = loadTenant(tenantName)
    if tenant:
        return tenant
    creationDate = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    tenant = {'Name': {'S': tenantName},
              'GroupName': tenantGroupInfo['GroupName'], 
              'RootS3Bucket':  tenantGroupInfo['BucketName'],
              'BasePrefix': {'S': tenantName + "/"},
              'Region':  tenantGroupInfo['Region'], 
              'Status': {'S': 'ACTIVE' }, 
              'Created-at': {'S': creationDate }, 
              'Updated-at': {'S': creationDate }  
             }
    persistToDatabase(tenant)
    return tenant


def persistToDatabase(tenant):
    print(tenant)
    try:
        dynamodb.describe_table(TableName=TENANTS_TABLE_NAME)
        print("Table already exists")
    except:
        print("Table not found")
        dynamodb.create_table(
            AttributeDefinitions=[
                {
                    'AttributeName': 'Name',
                    'AttributeType': 'S'
                }
            ],
            TableName=TENANTS_TABLE_NAME,
            KeySchema=[
                {
                    'AttributeName': 'Name',
                    'KeyType': 'HASH'
                }
            ],
            ProvisionedThroughput={
                'ReadCapacityUnits': 1,
                'WriteCapacityUnits': 1
            }
        )
        print("Table created")
        # Wait until the table exists.
        dynamodb.get_waiter('table_exists').wait(TableName=TENANTS_TABLE_NAME)
        # Print out some data about the table.
        response = dynamodb.describe_table(TableName=TENANTS_TABLE_NAME)
        print(response)


    dynamodb.put_item(TableName=TENANTS_TABLE_NAME,
                    Item=tenant)
    

def createSQSQueue(queueName):
    sqs = boto3.client('sqs')
    try:
        response = sqs.get_queue_url(
                    QueueName=queueName,
                    QueueOwnerAWSAccountId=ACCOUNT
                )
        print("Queue exists")
    except sqs.exceptions.QueueDoesNotExist:
        print("Queue not found")
        response = sqs.create_queue(
                    QueueName=queueName,
                    Attributes={
                        'DelaySeconds': '60',
                        'MessageRetentionPeriod': '86400'
                    }
                )
    queueURL = response['QueueUrl']
    updateQueuePolicy(sqs,queueURL)

        

def updateQueuePolicy(sqs,queueURL):
    queueArn = sqs.get_queue_attributes(
                        QueueUrl=queueURL,
                        AttributeNames=['QueueArn'])['Attributes']['QueueArn']
    policy = {
        "Version": "2012-10-17",
        "Statement": [
            {
                "Sid": "allow-lambda",
                "Effect": "Allow",
                "Action": "sqs:SendMessage",
                "Principal": {
                    "Service": "lambda.amazonaws.com"
                },
                "Resource": queueArn,
            }
        ]
    }
    sqs.set_queue_attributes(
        QueueUrl=queueURL,
        Attributes={
            'Policy': json.dumps(policy)
        }
    )
    print("Queue policy updated")

def writeToTenantPrefixes(tenant):
    s3 = boto3.client('s3')
    tenantName=tenant['Name']['S']
    tenantInfo = { "name": tenantName,
                   "group": tenant['GroupName']['S'],
                   "created-at": tenant['Created-at']['S'] }
    bucketName=tenant['RootS3Bucket']['S']
    s3.put_object(Body=json.dumps(tenantInfo), Bucket=bucketName, Key=tenantName + "/raw/" + "tenant.json")
    print("Tenant information written to " + bucketName + "/" + tenantName + "/raw/" + "tenant.json")
    s3.put_object(Body=json.dumps(tenantInfo), Bucket=bucketName, Key=tenantName + "/silver/" + "tenant.json")

def usage():
    print("Usage: python createTenant.py [-h | --help] [ -g tenant-group-name -n tenant-name ]")
    print("Example: python createTenant.py -g tenant-group-1 -n tenant-1")
    sys.exit(1)

def processArguments():
    try:
        opts, args = getopt.getopt(sys.argv[1:], "hg", ["help","tenant_group"])
    except getopt.GetoptError as err:
        usage()
    
    for opt, arg in opts:
        if opt in ("-h", "--help"):
            usage()
        elif opt in ("-g", "--tenant_group"):
            TENANT_GROUP_NAME = arg
        elif opt in ("-n", "--tenant_name"):
            TENANT_NAME = arg


# Create a new tenant within a given group: create a prefix under the bucket for the group of tenants
# 
if __name__ == '__main__':
    processArguments()
    tenantGroup=loadTenantGroupInformation(TENANT_GROUP_NAME)
    print(json.dumps(tenantGroup, indent=3))
    tenant=getOrCreateTenant(TENANT_NAME,tenantGroup)
    print(json.dumps(tenant,indent=3))
    writeToTenantPrefixes(tenant)
    createSQSQueue(TENANT_NAME+"-raw")
