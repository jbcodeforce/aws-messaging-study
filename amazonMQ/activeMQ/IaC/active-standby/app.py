#!/usr/bin/env python3
import os

import aws_cdk as cdk

from activeMQ.active_standby_stack import ActiveStandbyStack


app = cdk.App()
ActiveStandbyStack(app, "AMQStack", "DemoVpc","5.17.6",
   env=cdk.Environment(account=os.getenv('CDK_DEFAULT_ACCOUNT'), region=os.getenv('CDK_DEFAULT_REGION')),
)

app.synth()
